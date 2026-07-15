package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventEmitterServiceTest {

    @Mock private EventEmitterRepository repository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper() {{
        registerModule(new JavaTimeModule());
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }};
    private EventEmitterService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        service = new EventEmitterService(0, 10_000);
        setField(service, "repository", repository);
        setField(service, "objectMapper", objectMapper);
    }

    @Test
    void emit_createsEventAndDelegates() {
        service.emit(EventType.RESERVATION_DENIED, "t1", "scope/path",
                Actor.builder().type(ActorType.API_KEY).build(),
                EventDataReservationDenied.builder().reasonCode("BUDGET_EXCEEDED").build(),
                "corr-1", "req-1");

        // Async — poll the mock until the executor callback runs (deadline 5s).
        verify(repository, timeout(5000)).emit(argThat(e ->
                e.getEventType() == EventType.RESERVATION_DENIED &&
                e.getTenantId().equals("t1") &&
                e.getSource().equals("cycles-server") &&
                e.getScope().equals("scope/path") &&
                e.getCorrelationId().equals("corr-1")));
    }

    @Test
    void emit_nullData_works() {
        service.emit(EventType.SYSTEM_WEBHOOK_TEST, "t1", null, null, null, null, null);

        verify(repository, timeout(5000)).emit(argThat(e ->
                e.getEventType() == EventType.SYSTEM_WEBHOOK_TEST &&
                e.getData() == null));
    }

    @Test
    void emit_exceptionInRepo_doesNotThrow() {
        doThrow(new RuntimeException("fail")).when(repository).emit(any());

        // Sync call MUST NOT throw — async callback's try/catch swallows the repo failure.
        service.emit(EventType.RESERVATION_DENIED, "t1", null, null, null, null, null);

        // Wait until the async callback actually executes repository.emit so we know the
        // catch branch was exercised. If the exception leaked, this verify would still pass
        // but the test would have thrown synchronously above.
        verify(repository, timeout(5000)).emit(any());
    }

    @Test
    void emit_exceptionWithActorIsContainedAndNullTraceDefaults() {
        doThrow(new RuntimeException("fail")).when(repository).emit(any());

        service.emit(EventType.RESERVATION_DENIED, "t1", null,
            Actor.builder().type(ActorType.API_KEY).build(), null, null, null, null);

        verify(repository, timeout(5000)).emit(any());
    }

    @Test
    void emit_queueFull_dropsNonBlockingEventWithoutThrowing() throws Exception {
        EventEmitterService bounded = new EventEmitterService(1, 1);
        setField(bounded, "repository", repository);
        setField(bounded, "objectMapper", objectMapper);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(repository).emit(any());

        bounded.emit(EventType.RESERVATION_DENIED, "t1", null, null, null, "corr-1", "req-1");
        firstStarted.await(5, TimeUnit.SECONDS);
        bounded.emit(EventType.RESERVATION_DENIED, "t1", null, null, null, "corr-2", "req-2");

        // Worker is busy and the single queue slot is full; this must fail open
        // without blocking or throwing from the request path.
        bounded.emit(EventType.RESERVATION_DENIED, "t1", null,
            Actor.builder().type(ActorType.API_KEY).build(), null, "corr-3", "req-3");

        release.countDown();
        verify(repository, timeout(5000).times(2)).emit(any());
        bounded.destroy();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = EventEmitterService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void destroy_shutsDownExecutor() {
        // Should not throw
        service.destroy();
    }

    // --- emitBalanceEvents tests ---

    @Test
    void emitBalanceEvents_exhausted_emitsWithBudgetThresholdData() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, 0L))
                .allocated(new Amount(Enums.UnitEnum.USD_MICROCENTS, 10000L))
                .spent(new Amount(Enums.UnitEnum.USD_MICROCENTS, 8000L))
                .reserved(new Amount(Enums.UnitEnum.USD_MICROCENTS, 2000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();

        service.emitBalanceEvents(List.of(b), "t1", actor, null, null);

        verify(repository, timeout(5000)).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_EXHAUSTED &&
                e.getTenantId().equals("t1") &&
                e.getScope().equals("tenant:t1") &&
                e.getData() != null &&
                e.getData().get("threshold").equals(1.0) &&
                e.getData().get("direction").equals("rising") &&
                e.getData().get("remaining").equals(0L)));
    }

    @Test
    void emitBalanceEvents_overLimitEntered_emitsWhenOverLimit() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, -500L))
                .debt(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1500L))
                .overdraftLimit(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1000L))
                .isOverLimit(true)
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();

        service.emitBalanceEvents(List.of(b), "t1", actor, "corr-1", "req-1");

        verify(repository, timeout(5000)).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_OVER_LIMIT_ENTERED &&
                e.getTenantId().equals("t1") &&
                e.getData() != null));
    }

    @Test
    void emitBalanceEvents_debtIncurred_emitsWhenNewDebtCreated() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, -200L))
                .debt(new Amount(Enums.UnitEnum.USD_MICROCENTS, 200L))
                .overdraftLimit(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();
        Map<String, Long> scopeDebt = Map.of("tenant:t1", 200L);

        service.emitBalanceEvents(List.of(b), "t1", actor,
                null, null, scopeDebt, null, null);

        verify(repository, timeout(5000)).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_DEBT_INCURRED &&
                e.getTenantId().equals("t1")));
    }

    @Test
    void emitBalanceEvents_debtIncurred_doesNotEmitWhenNoNewDebt() throws Exception {
        // debt > 0 but no new debt in this operation — should NOT emit
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, -200L))
                .debt(new Amount(Enums.UnitEnum.USD_MICROCENTS, 200L))
                .overdraftLimit(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();

        // No scopeDebtIncurred entry — debt existed from prior operation
        service.emitBalanceEvents(List.of(b), "t1", actor, null, null);

        // Wait 200ms for the async to have a chance to run, then assert it did NOT emit.
        verify(repository, after(200).never()).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_DEBT_INCURRED));
    }

    @Test
    void emitBalanceEvents_debtIncurred_populatesReservationContext() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, -200L))
                .debt(new Amount(Enums.UnitEnum.USD_MICROCENTS, 200L))
                .overdraftLimit(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();
        Map<String, Long> scopeDebt = Map.of("tenant:t1", 200L);

        service.emitBalanceEvents(List.of(b), "t1", actor,
                "res-123", "ALLOW_WITH_OVERDRAFT", scopeDebt, null, null);

        verify(repository, timeout(5000)).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_DEBT_INCURRED &&
                e.getData() != null &&
                "res-123".equals(e.getData().get("reservation_id")) &&
                "ALLOW_WITH_OVERDRAFT".equals(e.getData().get("overage_policy"))));
    }

    @Test
    void emitBalanceEvents_debtIncurred_populatesPerScopeDebtIncurred() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, -200L))
                .debt(new Amount(Enums.UnitEnum.USD_MICROCENTS, 200L))
                .overdraftLimit(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();
        Map<String, Long> scopeDebt = Map.of("tenant:t1", 150L);

        service.emitBalanceEvents(List.of(b), "t1", actor,
                "res-456", "ALLOW_WITH_OVERDRAFT", scopeDebt, null, null);

        verify(repository, timeout(5000)).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_DEBT_INCURRED &&
                e.getData() != null &&
                Long.valueOf(150L).equals(((Number) e.getData().get("debt_incurred")).longValue()) &&
                "res-456".equals(e.getData().get("reservation_id"))));
    }

    // --- Transition detection tests (cycles-server-events#15) ---

    @Test
    void emitBalanceEvents_exhausted_doesNotEmitWhenAlreadyExhausted() throws Exception {
        // remaining == 0 but pre_remaining was also 0 — should NOT emit (not a transition)
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, 0L))
                .allocated(new Amount(Enums.UnitEnum.USD_MICROCENTS, 10000L))
                .spent(new Amount(Enums.UnitEnum.USD_MICROCENTS, 10000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();
        Map<String, Long> preRem = Map.of("tenant:t1", 0L);

        service.emitBalanceEvents(List.of(b), "t1", actor,
                null, null, null, preRem, null, null, null);

        // Wait 200ms for the async to have a chance to run, then assert it did NOT emit.
        verify(repository, after(200).never()).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_EXHAUSTED));
    }

    @Test
    void emitBalanceEvents_overLimitEntered_doesNotEmitWhenAlreadyOverLimit() throws Exception {
        // is_over_limit == true but was already true — should NOT emit (not a transition)
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, -500L))
                .debt(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1500L))
                .overdraftLimit(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1000L))
                .isOverLimit(true)
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();
        Map<String, Boolean> preOvl = Map.of("tenant:t1", true);

        service.emitBalanceEvents(List.of(b), "t1", actor,
                null, null, null, null, preOvl, null, null);

        // Wait 200ms for the async to have a chance to run, then assert it did NOT emit.
        verify(repository, after(200).never()).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_OVER_LIMIT_ENTERED));
    }

    @Test
    void emitBalanceEvents_noEventsForHealthyBalance() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, 5000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();

        service.emitBalanceEvents(List.of(b), "t1", actor, null, null);

        // Wait 200ms for the async to have a chance to run, then assert it did NOT emit.
        verify(repository, after(200).never()).emit(any());
    }

    @Test
    void emitBalanceEvents_nullOrEmptyBalances_noOp() {
        service.emitBalanceEvents(null, "t1", null, null, null);
        service.emitBalanceEvents(List.of(), "t1", null, null, null);

        // Wait 200ms for the async to have a chance to run, then assert it did NOT emit.
        verify(repository, after(200).never()).emit(any());
    }

    @Test
    void emitBalanceEvents_multipleScopes_emitsPerScope() throws Exception {
        Balance b1 = Balance.builder()
                .scope("tenant:t1").scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, 0L))
                .build();
        Balance b2 = Balance.builder()
                .scope("agent:bot").scopePath("tenant:t1/agent:bot")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, 0L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();

        service.emitBalanceEvents(List.of(b1, b2), "t1", actor, null, null);

        verify(repository, timeout(5000).times(2)).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_EXHAUSTED));
    }

    @Test
    void emitBalanceEvents_coversSparseAndZeroValuedBalanceFields() {
        Enums.UnitEnum unit = Enums.UnitEnum.USD_MICROCENTS;
        Balance noRemaining = Balance.builder().scopePath("s0").isOverLimit(false).build();
        Balance nullRemainingAmount = Balance.builder().scopePath("s-null")
            .remaining(new SignedAmount(unit, null)).build();
        Balance nullAllocated = Balance.builder().scopePath("s1")
            .remaining(new SignedAmount(unit, 0L)).build();
        Balance zeroAllocated = Balance.builder().scopePath("s2")
            .remaining(new SignedAmount(unit, 0L)).allocated(new Amount(unit, 0L)).build();
        Balance nullUsage = Balance.builder().scopePath("s3")
            .remaining(new SignedAmount(unit, 0L)).allocated(new Amount(unit, 10L)).build();
        Balance noDebtFields = Balance.builder().scopePath("s4")
            .isOverLimit(true).build();
        Balance noOverdraftLimit = Balance.builder().scopePath("s5")
            .debt(new Amount(unit, 5L)).isOverLimit(true).build();
        Balance zeroOverdraftLimit = Balance.builder().scopePath("s6")
            .debt(new Amount(unit, 5L)).overdraftLimit(new Amount(unit, 0L)).isOverLimit(true).build();

        service.emitBalanceEvents(List.of(noRemaining, nullRemainingAmount, nullAllocated, zeroAllocated, nullUsage,
                noDebtFields, noOverdraftLimit, zeroOverdraftLimit),
            "t1", null, null, null, Map.of("s0", 0L), null, null, null, null, null);

        verify(repository, timeout(5000).atLeast(6)).emit(any());
    }

    @Test
    void emitBalanceEvents_debtConditionEvaluatesEveryShortCircuit() {
        Enums.UnitEnum unit = Enums.UnitEnum.USD_MICROCENTS;
        Balance noDebt = Balance.builder().scopePath("no-debt").build();
        Balance validDebtWithoutLimit = Balance.builder().scopePath("valid")
            .debt(new Amount(unit, 7L)).build();
        Map<String, Long> perScope = new java.util.HashMap<>();
        perScope.put("no-debt", 3L);
        perScope.put("valid", 7L);
        perScope.put("zero", 0L);
        Balance zero = Balance.builder().scopePath("zero")
            .debt(new Amount(unit, 1L)).build();
        Balance nullDebtAmount = Balance.builder().scopePath("null-amount")
            .debt(new Amount(unit, null)).build();
        perScope.put("null-amount", 3L);

        service.emitBalanceEvents(List.of(noDebt, zero, nullDebtAmount, validDebtWithoutLimit), "t1", null,
            "res", "ALLOW_WITH_OVERDRAFT", perScope, null, null, null, null, null);

        verify(repository, timeout(5000)).emit(argThat(event ->
            event.getEventType() == EventType.BUDGET_DEBT_INCURRED
                && "valid".equals(event.getScope())));
    }
}
