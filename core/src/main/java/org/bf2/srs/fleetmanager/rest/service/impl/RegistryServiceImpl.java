package org.bf2.srs.fleetmanager.rest.service.impl;

import io.quarkus.security.identity.SecurityIdentity;
import org.apache.commons.lang3.tuple.Pair;
import org.bf2.srs.fleetmanager.auth.AuthService;
import org.bf2.srs.fleetmanager.auth.interceptor.CheckDeletePermissions;
import org.bf2.srs.fleetmanager.auth.interceptor.CheckReadPermissions;
import org.bf2.srs.fleetmanager.common.operation.auditing.Audited;
import org.bf2.srs.fleetmanager.common.storage.RegistryNotFoundException;
import org.bf2.srs.fleetmanager.common.storage.RegistryStorageConflictException;
import org.bf2.srs.fleetmanager.common.storage.ResourceStorage;
import org.bf2.srs.fleetmanager.common.storage.model.RegistryData;
import org.bf2.srs.fleetmanager.common.storage.util.QueryConfig;
import org.bf2.srs.fleetmanager.common.storage.util.QueryResult;
import org.bf2.srs.fleetmanager.common.storage.util.SearchQuery;
import org.bf2.srs.fleetmanager.execution.impl.tasks.ScheduleRegistryTask;
import org.bf2.srs.fleetmanager.execution.impl.tasks.deprovision.StartDeprovisionRegistryTask;
import org.bf2.srs.fleetmanager.execution.manager.TaskManager;
import org.bf2.srs.fleetmanager.rest.service.RegistryService;
import org.bf2.srs.fleetmanager.rest.service.convert.ConvertRegistry;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryCreateDto;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryDto;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryInstanceTypeValueDto;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryListDto;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryStatusValueDto;
import org.bf2.srs.fleetmanager.rest.service.model.ServiceStatusDto;
import org.bf2.srs.fleetmanager.rest.service.model.UsageStatisticsDto;
import org.bf2.srs.fleetmanager.spi.ams.AccountManagementService;
import org.bf2.srs.fleetmanager.spi.ams.AccountManagementServiceException;
import org.bf2.srs.fleetmanager.spi.ams.ResourceLimitReachedException;
import org.bf2.srs.fleetmanager.spi.ams.TermsRequiredException;
import org.bf2.srs.fleetmanager.spi.common.EvalInstancesNotAllowedException;
import org.bf2.srs.fleetmanager.spi.common.TooManyEvalInstancesForUserException;
import org.bf2.srs.fleetmanager.spi.common.TooManyInstancesException;
import org.bf2.srs.fleetmanager.spi.common.model.AccountInfo;
import org.bf2.srs.fleetmanager.spi.common.model.ResourceType;
import org.bf2.srs.fleetmanager.util.BasicQuery;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.validation.ValidationException;

import static org.bf2.srs.fleetmanager.common.operation.auditing.AuditingConstants.KEY_REGISTRY_ID;
import static org.bf2.srs.fleetmanager.util.SecurityUtil.OWNER_ID_PLACEHOLDER;
import static org.bf2.srs.fleetmanager.util.SecurityUtil.isResolvable;

@ApplicationScoped
public class RegistryServiceImpl implements RegistryService {

    @Inject
    TaskManager tasks;

    @Inject
    ResourceStorage storage;

    @Inject
    ConvertRegistry convertRegistry;

    @Inject
    Instance<SecurityIdentity> securityIdentity;

    @Inject
    AuthService authService;

    @Inject
    AccountManagementService accountManagementService;

    @ConfigProperty(name = "srs-fleet-manager.registry.instances.eval.enabled")
    boolean evalInstancesEnabled;

    @ConfigProperty(name = "srs-fleet-manager.registry.instances.eval.only")
    boolean evalInstancesOnlyEnabled;

    @ConfigProperty(name = "srs-fleet-manager.registry.instances.eval.max-count-per-user")
    int maxEvalInstancesPerUser;

    @ConfigProperty(name = "srs-fleet-manager.registry.instances.max-count")
    int maxInstances;

    @Audited
    @Override
    public RegistryDto createRegistry(RegistryCreateDto registryCreate)
            throws RegistryStorageConflictException, TermsRequiredException, ResourceLimitReachedException,
            EvalInstancesNotAllowedException, TooManyEvalInstancesForUserException, TooManyInstancesException,
            AccountManagementServiceException {
        final AccountInfo accountInfo = authService.extractAccountInfo();

        // Make sure we have more instances available (max capacity not yet reached).
        long instanceCount = storage.getRegistryCountTotal();
        if (instanceCount >= maxInstances) {
            throw new TooManyInstancesException();
        }

        // Figure out if we are going to create a standard or eval instance.
        ResourceType resourceType = evalInstancesOnlyEnabled ?
                ResourceType.REGISTRY_INSTANCE_EVAL : accountManagementService.determineAllowedResourceType(accountInfo);

        if (resourceType == ResourceType.REGISTRY_INSTANCE_EVAL) {
            // Are eval instances allowed?
            if (!evalInstancesEnabled) {
                throw new EvalInstancesNotAllowedException();
            }

            // Limit the # of eval instances per user.  Need to check storage for list of eval registry instances.
            List<RegistryData> registriesByOwner = storage.getRegistriesByOwner(accountInfo.getAccountUsername());
            int evalInstanceCount = 0;
            for (RegistryData registryData : registriesByOwner) { // TODO Perform a dedicated query
                if (RegistryInstanceTypeValueDto.EVAL.value().equals(registryData.getInstanceType())) {
                    evalInstanceCount++;
                }
            }
            if (evalInstanceCount >= maxEvalInstancesPerUser) {
                throw new TooManyEvalInstancesForUserException();
            }
        }

        // Try to consume some quota from AMS for the appropriate resource type (standard or eval).  If successful
        // we'll get back a subscriptionId - if not we'll throw an exception.
        String subscriptionId = accountManagementService.createResource(accountInfo, resourceType);

        // Convert to registry data and persist it in the DB.
        RegistryInstanceTypeValueDto instanceType = resourceTypeToInstanceType(resourceType);

        RegistryData registryData = convertRegistry.convert(registryCreate, subscriptionId, accountInfo.getAccountUsername(),
                accountInfo.getOrganizationId(), accountInfo.getAccountId(), instanceType);
        // Generate the ID
        registryData.setId(UUID.randomUUID().toString());
        storage.createOrUpdateRegistry(registryData);
        tasks.submit(ScheduleRegistryTask.builder().registryId(registryData.getId()).build());
        return convertRegistry.convert(registryData);
    }

    private static RegistryInstanceTypeValueDto resourceTypeToInstanceType(ResourceType resourceType) {
        return resourceType == ResourceType.REGISTRY_INSTANCE_STANDARD ? RegistryInstanceTypeValueDto.STANDARD : RegistryInstanceTypeValueDto.EVAL;
    }

    @Override
    public RegistryListDto getRegistries(Integer page, Integer size, String orderBy, String search) {
        // Defaults
        var queryConfigBuilder = QueryConfig.builder()
                .sortDirection(QueryConfig.Direction.ASCENDING)
                .sortColumn("id")
                .index(page != null ? page - 1 : 0)
                .size(size != null ? size : 10); // TODO Max size?

        if (orderBy != null) {
            var order = orderBy.split(" ");
            if (order.length != 2) {
                throw new ValidationException("invalid orderBy");
            }
            queryConfigBuilder.sortColumn(order[0]);
            if ("asc".equals(order[1])) {
                queryConfigBuilder.sortDirection(QueryConfig.Direction.ASCENDING);
            } else {
                queryConfigBuilder.sortDirection(QueryConfig.Direction.DESCENDING);
            }
        }

        List<Pair<String, Object>> conditions = new ArrayList<>();
        if (search != null && !search.isEmpty()) {
            var basicQuery = new BasicQuery(search, Arrays.asList("name", "status"));
            conditions.add(Pair.of(basicQuery.getColumn(), basicQuery.getArgument()));
        }

        //list only registries from your organization or the ones the user owns
        if (isResolvable(securityIdentity)) {
            final AccountInfo accountInfo = authService.extractAccountInfo();
            String orgId = accountInfo.getOrganizationId();
            if (orgId != null) {
                conditions.add(Pair.of("orgId", orgId));
            } else {
                conditions.add(Pair.of("ownerId", accountInfo.getAccountId()));
            }
        } else {
            conditions.add(Pair.of("ownerId", OWNER_ID_PLACEHOLDER));
        }

        var query = new SearchQuery(conditions);
        var queryConfig = queryConfigBuilder.build();

        QueryResult<RegistryData> queryResult = storage.executeRegistrySearchQuery(query, queryConfig);

        var items = queryResult.getItems().stream().map(convertRegistry::convert)
                .collect(Collectors.toList());
        return RegistryListDto.builder()
                .items(items)
                .page(queryConfig.getIndex() + 1)
                .size(queryConfig.getSize())
                .total(queryResult.getCount())
                .build();
    }

    @Override
    @CheckReadPermissions
    public RegistryDto getRegistry(String registryId) throws RegistryNotFoundException {
        try {
            return storage.getRegistryById(registryId)
                    .map(convertRegistry::convert)
                    .orElseThrow(() -> new RegistryNotFoundException(registryId));
        } catch (NumberFormatException ex) {
            throw new RegistryNotFoundException(registryId);
        }
    }

    @Override
    @Audited(extractParameters = {"0", KEY_REGISTRY_ID})
    @CheckDeletePermissions
    public void deleteRegistry(String registryId) throws RegistryNotFoundException, RegistryStorageConflictException {
        try {
            // Verify preconditions - Registry exists
            storage.getRegistryById(registryId).orElseThrow(() -> new RegistryNotFoundException(registryId));
            tasks.submit(StartDeprovisionRegistryTask.builder().registryId(registryId).build());
        } catch (NumberFormatException ex) {
            throw new RegistryNotFoundException(registryId);
        }
    }

    @Override
    public ServiceStatusDto getServiceStatus() {
        long total = storage.getRegistryCountTotal();

        ServiceStatusDto status = new ServiceStatusDto();
        status.setMaxInstancesReached(total >= maxInstances);
        return status;
    }

    @Override
    public UsageStatisticsDto getUsageStatistics() {
        var countPerStatusConverted = new EnumMap<RegistryStatusValueDto, Long>(RegistryStatusValueDto.class);
        var countPerStatus = storage.getRegistryCountPerStatus();
        for (var entry : RegistryStatusValueDto.getConstants().entrySet()) {
            countPerStatusConverted.put(entry.getValue(), countPerStatus.getOrDefault(entry.getKey(), 0L));
        }

        var countPerTypeConverted = new EnumMap<RegistryInstanceTypeValueDto, Long>(RegistryInstanceTypeValueDto.class);
        var countPerType = storage.getRegistryCountPerType();
        for (var entry : RegistryInstanceTypeValueDto.getConstants().entrySet()) {
            countPerTypeConverted.put(entry.getValue(), countPerType.getOrDefault(entry.getKey(), 0L));
        }

        return UsageStatisticsDto.builder()
                .registryCountPerStatus(countPerStatusConverted)
                .registryCountPerType(countPerTypeConverted)
                .activeUserCount(storage.getRegistryOwnerCount())
                .activeOrganisationCount(storage.getRegistryOrganisationCount())
                .build();
    }
}
