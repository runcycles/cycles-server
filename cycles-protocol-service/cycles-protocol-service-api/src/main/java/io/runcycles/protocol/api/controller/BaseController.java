package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.Enums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

abstract public class BaseController {
    private static final Logger LOG = LoggerFactory.getLogger(BaseController.class);

    public String extractAuthTenantId(){
        ApiKeyAuthentication auth = (ApiKeyAuthentication) SecurityContextHolder
                .getContext()
                .getAuthentication();
        if (auth != null){
            return auth.getTenantId();
        }
        return null;
    }
    public void authorizeTenant (String tenantFromRequest){
        LOG.info("Authorizing tenant: tenantFromRequest={}",tenantFromRequest);
        String tenantFromAuthorization = extractAuthTenantId ();
        if (tenantFromRequest != null && !tenantFromRequest.isBlank()){
            if (!tenantFromRequest.equals(tenantFromAuthorization)){
                throw new CyclesProtocolException(Enums.ErrorCode.FORBIDDEN, "Tenant provided in the request body does not match tenant resolved from authorization token",403) ;
            }
        }
        LOG.info("Authorization status: request is not tenant based, or tenant provided in request matches the one resolved from API key: tenantFromRequest={},tenantFromAuthorization={}",tenantFromRequest,tenantFromAuthorization);
    }

    public void validateIdempotencyHeader(String headerKey, String bodyKey) {
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw new CyclesProtocolException(Enums.ErrorCode.IDEMPOTENCY_MISMATCH,
                "X-Idempotency-Key header does not match idempotency_key in request body", 409);
        }
    }
}
