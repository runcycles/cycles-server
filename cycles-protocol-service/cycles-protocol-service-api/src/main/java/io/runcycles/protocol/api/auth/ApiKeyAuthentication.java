package io.runcycles.protocol.api.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class ApiKeyAuthentication extends AbstractAuthenticationToken {
    private final String apiKey;
    private final String tenantId;
    private final String keyId;
    private final List<String> permissions;

    public ApiKeyAuthentication(
            String apiKey,
            String tenantId,
            String keyId,
            List<String> permissions) {

        super(permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .toList());

        this.apiKey = apiKey;
        this.tenantId = tenantId;
        this.keyId = keyId;
        this.permissions = permissions;
        setAuthenticated(true);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getKeyId() {
        return keyId;
    }

    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return tenantId;
    }
}
