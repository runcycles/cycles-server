package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.auth.AdminApiKeyAuthentication;
import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.api.filter.TraceContextFilter;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.util.TraceContext;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.Subject;
import io.runcycles.protocol.model.event.Actor;
import io.runcycles.protocol.model.event.ActorType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

abstract public class BaseController {
    private static final Logger LOG = LoggerFactory.getLogger(BaseController.class);

    public String extractAuthTenantId(){
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication apiAuth) {
            return apiAuth.getTenantId();
        }
        // Admin auth has no effective tenant — admin callers must
        // specify it explicitly (e.g. via the ?tenant query param on
        // listReservations) or implicitly via a path-templated id.
        return null;
    }

    /**
     * v0.1.25.8 (cycles-protocol revision 2026-04-13): admin-on-behalf-of
     * marker. True when this request was authenticated via X-Admin-API-Key
     * on a dual-auth-allowlisted endpoint. Controllers branch on this to:
     *   - skip tenant ownership checks (admin can act across tenants)
     *   - require explicit tenant in query params (no effective tenant)
     *   - tag emitted events with ActorType.ADMIN_ON_BEHALF_OF
     */
    protected boolean isAdminAuth() {
        return SecurityContextHolder.getContext().getAuthentication()
            instanceof AdminApiKeyAuthentication;
    }

    /**
     * Build Actor from current auth context and request. For tenant-key
     * callers: ActorType.API_KEY with the resolved key_id. For admin
     * callers: ActorType.ADMIN_ON_BEHALF_OF with no key_id (admin keys
     * aren't persisted as ApiKey records — they're a server-config
     * value compared in the filter).
     */
    protected Actor buildActor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String sourceIp = request != null ? request.getRemoteAddr() : null;
        if (auth instanceof AdminApiKeyAuthentication) {
            return Actor.builder()
                    .type(ActorType.ADMIN_ON_BEHALF_OF)
                    .sourceIp(sourceIp)
                    .build();
        }
        String keyId = auth instanceof ApiKeyAuthentication apiAuth ? apiAuth.getKeyId() : null;
        return Actor.builder()
                .type(ActorType.API_KEY)
                .keyId(keyId)
                .sourceIp(sourceIp)
                .build();
    }
    public void authorizeTenant (String tenantFromRequest){
        // Admin callers bypass tenant ownership checks — they're allowed
        // to act across tenants on the small allowlisted set of
        // reservation endpoints. The filter has already validated the
        // admin key; controllers handle the explicit tenant scoping
        // (e.g. listReservations requires ?tenant when admin).
        if (isAdminAuth()) return;
        LOG.debug("Authorizing tenant: tenantFromRequest={}",tenantFromRequest);
        String tenantFromAuthorization = extractAuthTenantId ();
        if (tenantFromRequest != null && !tenantFromRequest.isBlank()){
            if (!tenantFromRequest.equals(tenantFromAuthorization)){
                throw new CyclesProtocolException(Enums.ErrorCode.FORBIDDEN, "Tenant provided in the request body does not match tenant resolved from authorization token",403) ;
            }
        }
        LOG.debug("Authorization status: request is not tenant based, or tenant provided in request matches the one resolved from API key: tenantFromRequest={},tenantFromAuthorization={}",tenantFromRequest,tenantFromAuthorization);
    }

    /** Return the X-Request-Id attribute set by RequestIdFilter, or null. */
    protected String resolveRequestId(HttpServletRequest request) {
        if (request == null) return null;
        Object v = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return v != null ? v.toString() : null;
    }

    /** Return the trace_id attribute set by TraceContextFilter, or null. */
    protected String resolveTraceId(HttpServletRequest request) {
        return TraceContextFilter.currentTraceId(request);
    }

    /** Return the full TraceContext (trace_id + trace_flags + inbound-W3C flag) captured by the filter. */
    protected TraceContext resolveTraceContext(HttpServletRequest request) {
        return TraceContextFilter.currentContext(request);
    }

    public void validateIdempotencyHeader(String headerKey, String bodyKey) {
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                "X-Idempotency-Key header does not match idempotency_key in request body", 400);
        }
    }

    /**
     * YAML spec: Subject uses anyOf — at least one standard field must be provided.
     * A Subject with only dimensions is invalid.
     */
    public void validateSubject(Subject subject) {
        if (subject == null || !subject.hasAtLeastOneStandardField()) {
            throw new CyclesProtocolException(Enums.ErrorCode.INVALID_REQUEST,
                "Subject must have at least one standard field (tenant, workspace, app, workflow, agent, or toolset)", 400);
        }
    }
}
