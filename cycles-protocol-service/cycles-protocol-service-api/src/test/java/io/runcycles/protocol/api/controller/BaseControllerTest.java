package io.runcycles.protocol.api.controller;

import io.runcycles.protocol.api.auth.ApiKeyAuthentication;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("BaseController")
class BaseControllerTest {

    // Concrete subclass for testing the abstract class
    private final BaseController controller = new BaseController() {};

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication("cyc_live_test", "acme-corp", List.of("reservations:create")));
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
}
