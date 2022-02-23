package org.bf2.srs.fleetmanager.spi.ams.impl.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apicurio.rest.client.error.ApicurioRestClientException;
import io.apicurio.rest.client.error.RestClientErrorHandler;
import io.apicurio.rest.client.util.IoUtil;
import org.bf2.srs.fleetmanager.spi.ams.impl.model.response.Error;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

public class AccountManagementErrorHandler implements RestClientErrorHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public ApicurioRestClientException handleErrorResponse(InputStream inputStream, int statusCode) {
        String res = IoUtil.toString(inputStream);
        Error errorEntity = null;
        try {
            // Try to parse it as an Error entity
            errorEntity = MAPPER.readValue(res, Error.class);
        } catch (JsonProcessingException e) {
            // Ignore and use the raw string
            log.warn("Could not parse Error entity from AMS response", e);
        }
        if(statusCode == 404) {
            return errorEntity != null ? new SubscriptionNotFoundAMSCException(errorEntity, statusCode)
                    : new SubscriptionNotFoundAMSCException(res, statusCode);
        } else {
            return errorEntity != null ? new AccountManagementSystemClientException(errorEntity, statusCode)
                    : new AccountManagementSystemClientException(res, statusCode);
        }
    }

    @Override
    public ApicurioRestClientException parseError(Exception e) {
        throw new AccountManagementSystemClientException(e);
    }

    @Override
    public ApicurioRestClientException parseInputSerializingError(JsonProcessingException e) {
        throw new AccountManagementSystemClientException(e);
    }
}