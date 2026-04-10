package io.runcycles.protocol.data.exception;

import io.runcycles.protocol.model.Enums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CyclesProtocolException")
class CyclesProtocolExceptionTest {

    @Test
    void shouldCreateNotFound() {
        CyclesProtocolException ex = CyclesProtocolException.notFound("res_123");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
        assertThat(ex.getMessage()).contains("res_123");
    }

    @Test
    void shouldCreateBudgetExceeded() {
        CyclesProtocolException ex = CyclesProtocolException.budgetExceeded("tenant:acme");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.BUDGET_EXCEEDED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("tenant:acme");
    }

    @Test
    void shouldCreateOverdraftLimitExceeded() {
        CyclesProtocolException ex = CyclesProtocolException.overdraftLimitExceeded("tenant:acme");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.OVERDRAFT_LIMIT_EXCEEDED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void shouldCreateDebtOutstanding() {
        CyclesProtocolException ex = CyclesProtocolException.debtOutstanding("tenant:acme");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.DEBT_OUTSTANDING);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void shouldCreateReservationFinalized() {
        CyclesProtocolException ex = CyclesProtocolException.reservationFinalized("res_123");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.RESERVATION_FINALIZED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void shouldCreateBudgetNotFound() {
        CyclesProtocolException ex = CyclesProtocolException.budgetNotFound("tenant:acme");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void shouldCreateIdempotencyMismatch() {
        CyclesProtocolException ex = CyclesProtocolException.idempotencyMismatch();

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.IDEMPOTENCY_MISMATCH);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void shouldCreateUnitMismatch() {
        CyclesProtocolException ex = CyclesProtocolException.unitMismatch();

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.UNIT_MISMATCH);
        assertThat(ex.getHttpStatus()).isEqualTo(400);
    }

    @Test
    void shouldCreateUnitMismatchWithDetails() {
        List<String> expected = List.of("USD_MICROCENTS", "CREDITS");
        CyclesProtocolException ex = CyclesProtocolException.unitMismatch(
                "tenant:rider", "TOKENS", expected);

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.UNIT_MISMATCH);
        assertThat(ex.getHttpStatus()).isEqualTo(400);
        assertThat(ex.getMessage()).contains("tenant:rider");
        assertThat(ex.getMessage()).contains("TOKENS");
        assertThat(ex.getMessage()).contains("USD_MICROCENTS");
        assertThat(ex.getDetails()).containsEntry("scope", "tenant:rider");
        assertThat(ex.getDetails()).containsEntry("requested_unit", "TOKENS");
        assertThat(ex.getDetails()).containsEntry("expected_units", expected);
    }

    @Test
    void shouldCreateUnitMismatchWithDetailsAllowingNulls() {
        CyclesProtocolException ex = CyclesProtocolException.unitMismatch(null, null, null);

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.UNIT_MISMATCH);
        assertThat(ex.getHttpStatus()).isEqualTo(400);
        assertThat(ex.getDetails()).isEmpty();
    }

    @Test
    void shouldCreateReservationExpired() {
        CyclesProtocolException ex = CyclesProtocolException.reservationExpired();

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.RESERVATION_EXPIRED);
        assertThat(ex.getHttpStatus()).isEqualTo(410);
    }

    @Test
    void shouldPreserveDetails() {
        Map<String, Object> details = Map.of("scope", "tenant:acme", "remaining", 0);
        CyclesProtocolException ex = new CyclesProtocolException(
                Enums.ErrorCode.BUDGET_EXCEEDED, "Budget exceeded", 409, details);

        assertThat(ex.getDetails()).containsEntry("scope", "tenant:acme");
        assertThat(ex.getDetails()).containsEntry("remaining", 0);
    }

    @Test
    void shouldHaveNullDetailsWhenNotProvided() {
        CyclesProtocolException ex = CyclesProtocolException.notFound("res_123");

        assertThat(ex.getDetails()).isNull();
    }

    @Test
    void shouldCreateBudgetFrozen() {
        CyclesProtocolException ex = CyclesProtocolException.budgetFrozen("tenant:acme");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.BUDGET_FROZEN);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("tenant:acme");
    }

    @Test
    void shouldCreateBudgetClosed() {
        CyclesProtocolException ex = CyclesProtocolException.budgetClosed("tenant:acme");

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.BUDGET_CLOSED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getMessage()).contains("tenant:acme");
    }

    @Test
    void shouldCreateReservationExpirationNotFound() {
        CyclesProtocolException ex = CyclesProtocolException.reservationExpirationNotFound();

        assertThat(ex.getErrorCode()).isEqualTo(Enums.ErrorCode.INTERNAL_ERROR);
        assertThat(ex.getHttpStatus()).isEqualTo(500);
        assertThat(ex.getMessage()).contains("expiration");
    }
}
