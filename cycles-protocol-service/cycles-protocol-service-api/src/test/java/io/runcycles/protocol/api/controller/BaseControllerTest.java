package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.api.auth.AdminApiKeyAuthentication;
import io.runcycles.protocol.api.filter.RequestIdFilter;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.Subject;
import io.runcycles.protocol.model.event.Actor;
import io.runcycles.protocol.model.event.ActorType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BaseController")
class BaseControllerTest {

    // Concrete subclass for testing the abstract class
    private final TestController controller = new TestController();

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live_test", "acme-corp", "key-test-001", List.of("reservations:create")));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ---- extractAuthTenantId ----

    @Test
    void shouldExtractTenantFromSecurityContext() {
        assertThat(controller.extractAuthTenantId()).isEqualTo("acme-corp");
    }

    @Test
    void shouldReturnNullWhenNoAuth() {
        SecurityContextHolder.clearContext();
        assertThat(controller.extractAuthTenantId()).isNull();
    }

    // ---- authorizeTenant ----

    @Test
    void shouldAuthorizeTenantMatch() {
        assertThatNoException().isThrownBy(() -> controller.authorizeTenant("acme-corp"));
    }

    @Test
    void shouldThrow403OnTenantMismatch() {
        assertThatThrownBy(() -> controller.authorizeTenant("other-tenant"))
                .isInstanceOf(CyclesProtocolException.class)
                .satisfies(ex -> {
                    CyclesProtocolException cpe = (CyclesProtocolException) ex;
                    assertThat(cpe.getErrorCode()).isEqualTo(Enums.ErrorCode.FORBIDDEN);
                    assertThat(cpe.getHttpStatus()).isEqualTo(403);
                });
    }

    @Test
    void shouldAllowNullTenantInRequest() {
        assertThatNoException().isThrownBy(() -> controller.authorizeTenant(null));
    }

    @Test
    void shouldAllowBlankTenantInRequest() {
        assertThatNoException().isThrownBy(() -> controller.authorizeTenant(""));
        assertThatNoException().isThrownBy(() -> controller.authorizeTenant("   "));
    }

    // ---- validateIdempotencyHeader ----

    @Test
    void shouldThrow400OnIdempotencyKeyMismatch() {
        assertThatThrownBy(() -> controller.validateIdempotencyHeader("header-key", "body-key"))
                .isInstanceOf(CyclesProtocolException.class)
                .satisfies(ex -> {
                    CyclesProtocolException cpe = (CyclesProtocolException) ex;
                    assertThat(cpe.getErrorCode()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
                    assertThat(cpe.getHttpStatus()).isEqualTo(400);
                });
    }

    @Test
    void shouldPassWhenOnlyHeaderKeyProvided() {
        assertThatNoException().isThrownBy(() -> controller.validateIdempotencyHeader("header-key", null));
    }

    @Test
    void shouldPassWhenOnlyBodyKeyProvided() {
        assertThatNoException().isThrownBy(() -> controller.validateIdempotencyHeader(null, "body-key"));
    }

    @Test
    void shouldPassWhenKeysMatch() {
        assertThatNoException().isThrownBy(() -> controller.validateIdempotencyHeader("same-key", "same-key"));
    }

    // ---- validateSubject ----

    @Test
    void shouldThrow400OnNullSubject() {
        assertThatThrownBy(() -> controller.validateSubject(null))
                .isInstanceOf(CyclesProtocolException.class)
                .satisfies(ex -> {
                    CyclesProtocolException cpe = (CyclesProtocolException) ex;
                    assertThat(cpe.getErrorCode()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
                    assertThat(cpe.getHttpStatus()).isEqualTo(400);
                });
    }

    @Test
    void shouldThrow400OnDimensionsOnlySubject() {
        Subject subject = new Subject();
        subject.setDimensions(Map.of("env", "prod"));

        assertThatThrownBy(() -> controller.validateSubject(subject))
                .isInstanceOf(CyclesProtocolException.class)
                .satisfies(ex -> {
                    CyclesProtocolException cpe = (CyclesProtocolException) ex;
                    assertThat(cpe.getErrorCode()).isEqualTo(Enums.ErrorCode.INVALID_REQUEST);
                });
    }

    @Test
    void shouldPassValidSubject() {
        Subject subject = new Subject();
        subject.setTenant("acme-corp");

        assertThatNoException().isThrownBy(() -> controller.validateSubject(subject));
    }

    @Test
    void adminAuthBypassesTenantChecksAndBuildsAdminActor() {
        SecurityContextHolder.getContext().setAuthentication(new AdminApiKeyAuthentication("admin"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertThatNoException().isThrownBy(() -> controller.authorizeTenant("other"));
        assertThat(controller.extractAuthTenantId()).isNull();
        Actor actor = controller.actor(request);
        assertThat(actor.getType()).isEqualTo(ActorType.ADMIN_ON_BEHALF_OF);
        assertThat(actor.getSourceIp()).isEqualTo("127.0.0.1");
        assertThat(actor.getKeyId()).isNull();
    }

    @Test
    void actorRequestIdLoggingAndSanitizingCoverNullAndPopulatedContexts() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).thenReturn("request-1");

        assertThat(controller.actor(request).getKeyId()).isEqualTo("key-test-001");
        SecurityContextHolder.clearContext();
        assertThat(controller.actor(null).getType()).isEqualTo(ActorType.API_KEY);
        assertThat(controller.requestId(null)).isNull();
        assertThat(controller.requestId(request)).isEqualTo("request-1");
        assertThat(controller.safe(null)).isNull();
        assertThat(controller.safe("a\r\nb")).isEqualTo("a  b");

        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        controller.requestLog(logger, request);
        verify(logger).debug(anyString(), any(), any(), any(), any(), any(), any());
    }

    private static final class TestController extends BaseController {
        Actor actor(HttpServletRequest request) { return buildActor(request); }
        void require(String... permissions) { requirePermission(permissions); }
        String requestId(HttpServletRequest request) { return resolveRequestId(request); }
        String safe(Object value) { return safeLogValue(value); }
        void requestLog(Logger logger, HttpServletRequest request) {
            logRequest(logger, request, "operation", "tenant", "reservation");
        }
    }

    // ---- requirePermission ----

    private static void authWith(String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live_test", "acme-corp", "key-test-001", List.of(permissions)));
    }

    @Test
    void requirePermissionPassesWhenKeyHoldsEveryPermission() {
        authWith("reservations:create", "reservations:commit");
        assertThatNoException().isThrownBy(() ->
                controller.require("reservations:create", "reservations:commit"));
    }

    @Test
    void requirePermissionRejectsMissingPermissionWith403() {
        authWith("reservations:create");
        assertThatThrownBy(() -> controller.require("reservations:create", "reservations:commit"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.FORBIDDEN)
                .hasFieldOrPropertyWithValue("httpStatus", 403)
                .hasMessageContaining("reservations:commit");
    }

    @Test
    void requirePermissionRejectsKeyWithNoPermissions() {
        authWith();
        assertThatThrownBy(() -> controller.require("balances:read"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.FORBIDDEN);
    }

    @Test
    void adminReadWildcardSatisfiesReadPermissionsOnly() {
        authWith("admin:read");
        assertThatNoException().isThrownBy(() -> controller.require("balances:read"));
        // reservations:* are not *:read permissions - the wildcard must not cover them
        assertThatThrownBy(() -> controller.require("reservations:list"))
                .isInstanceOf(CyclesProtocolException.class);
        assertThatThrownBy(() -> controller.require("reservations:create"))
                .isInstanceOf(CyclesProtocolException.class);
    }

    @Test
    void requirePermissionIsBypassedForAdminAuth() {
        SecurityContextHolder.getContext().setAuthentication(new AdminApiKeyAuthentication("admin"));
        assertThatNoException().isThrownBy(() -> controller.require("reservations:release"));
    }

    @Test
    void requirePermissionRejectsWhenUnauthenticated() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> controller.require("balances:read"))
                .isInstanceOf(CyclesProtocolException.class)
                .hasFieldOrPropertyWithValue("errorCode", Enums.ErrorCode.FORBIDDEN);
    }

    @Test
    void requirePermissionCanBeDisabledByOperator() throws Exception {
        TestController relaxed = new TestController();
        java.lang.reflect.Field f = BaseController.class.getDeclaredField("enforcePermissions");
        f.setAccessible(true);
        f.setBoolean(relaxed, false);
        authWith();
        assertThatNoException().isThrownBy(() -> relaxed.require("reservations:create"));
    }
}
