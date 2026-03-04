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
        LOG.info("Validating API token: apiToken={}",apiToken);

        ApiKeyValidationResponse apiKeyValidationResponse = apiKeyRepository.validate(apiToken) ;
        LOG.info("API key validation result: result={}",apiKeyValidationResponse);
        return apiKeyValidationResponse ;
    }
}
