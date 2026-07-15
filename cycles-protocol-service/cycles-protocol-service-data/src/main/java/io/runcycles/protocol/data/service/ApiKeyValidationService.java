package io.runcycles.protocol.data.service;

import io.runcycles.protocol.data.repository.ApiKeyRepository;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyValidationService {
    private final ApiKeyRepository apiKeyRepository;
    private final Logger logger;

    @Autowired
    public ApiKeyValidationService(ApiKeyRepository apiKeyRepository) {
        this(apiKeyRepository, LoggerFactory.getLogger(ApiKeyValidationService.class));
    }

    ApiKeyValidationService(ApiKeyRepository apiKeyRepository, Logger logger) {
        this.apiKeyRepository = apiKeyRepository;
        this.logger = logger;
    }

    public ApiKeyValidationResponse isValid (String apiToken){
        ApiKeyValidationResponse apiKeyValidationResponse = apiKeyRepository.validate(apiToken) ;
        if (logger.isDebugEnabled()) {
            logger.debug("API key validation: api_key_present={} api_key_length={} valid={} tenant={} key_id={} reason={}",
                    apiToken != null && !apiToken.isBlank(), apiToken != null ? apiToken.length() : null,
                    apiKeyValidationResponse.isValid(), safeLogValue(apiKeyValidationResponse.getTenantId()),
                    safeLogValue(apiKeyValidationResponse.getKeyId()), safeLogValue(apiKeyValidationResponse.getReason()));
        }
        return apiKeyValidationResponse ;
    }

    static String safeLogValue(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().replace('\r', ' ').replace('\n', ' ');
    }
}
