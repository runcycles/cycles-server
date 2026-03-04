package io.runcycles.protocol.model.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyValidationResponse {
    @JsonProperty("valid") private Boolean valid;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonProperty("key_id") private String keyId;
    @JsonProperty("permissions") private List<String> permissions;
    @JsonProperty("scope_filter") private List<String> scopeFilter;
    @JsonProperty("expires_at") private Instant expiresAt;
    @JsonProperty("reason") private String reason;

    public boolean isValid (){
        return Boolean.TRUE.equals(valid);
    }
}
