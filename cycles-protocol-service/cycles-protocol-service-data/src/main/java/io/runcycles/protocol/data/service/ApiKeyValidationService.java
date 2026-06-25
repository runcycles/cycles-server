package io.runcycles.protocol.data.service;

import io.runcycles.protocol.data.repository.ApiKeyRepository;
import io.runcycles.protocol.model.auth.ApiKeyValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyValidationService {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyValidationService.class);

    @Autowired
    private ApiKeyRepository apiKeyRepository ;

    public ApiKeyValidationResponse isValid (String apiToken){
        ApiKeyValidationResponse apiKeyValidationResponse = apiKeyRepository.validate(apiToken) ;
        if (LOG.isDebugEnabled()) {
            LOG.debug("API key validation: api_key_present={} api_key_length={} valid={} tenant={} key_id={} reason={}",
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
