package io.runcycles.protocol.model.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Audit-log entry written to the shared Redis store used by the
 * governance plane's admin dashboard. Schema and Redis key layout
 * match {@code io.runcycles.admin.model.audit.AuditLogEntry} and
 * {@code io.runcycles.admin.data.repository.AuditRepository} exactly —
 * entries written here appear in the governance dashboard's Audit view
 * via the existing {@code GET /v1/admin/audit/logs} endpoint (which
 * reads from the same {@code audit:log:*} / {@code audit:logs:*}
 * indexes).
 *
 * <p>v0.1.25.8: introduced so the runtime plane can record admin-driven
 * releases, satisfying the NORMATIVE requirement in cycles-protocol
 * revision 2026-04-13 ("audit-log entry with actor_type=admin_on_behalf_of").
 *
 * <p>Kept as a parallel copy (rather than a Maven dep on the admin
 * service's model module) because the admin service isn't published as
 * an artifact, and cross-repo source deps would be heavier than a 30-
 * line mirror. Drift risk is mitigated by this doc comment + an
 * integration concern (entries failing to parse server-side would show
 * up in the Audit view as missing/malformed).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntry {
    @JsonProperty("log_id") private String logId;
    @JsonProperty("timestamp") private Instant timestamp;
    @JsonProperty("tenant_id") private String tenantId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("key_id") private String keyId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("user_agent") private String userAgent;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("source_ip") private String sourceIp;
    @JsonProperty("operation") private String operation;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resource_type") private String resourceType;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("resource_id") private String resourceId;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("request_id") private String requestId;
    @JsonProperty("status") private Integer status;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("error_code") private String errorCode;
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("metadata") private Map<String, Object> metadata;
}
