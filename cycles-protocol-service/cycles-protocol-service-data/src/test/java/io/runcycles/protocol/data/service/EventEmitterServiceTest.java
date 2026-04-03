package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.model.*;
import io.runcycles.protocol.model.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventEmitterServiceTest {

    @Mock private EventEmitterRepository repository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper() {{
        registerModule(new JavaTimeModule());
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }};
    @InjectMocks private EventEmitterService service;

    @Test
    void emit_createsEventAndDelegates() throws Exception {
        service.emit(EventType.RESERVATION_DENIED, "t1", "scope/path",
                Actor.builder().type(ActorType.API_KEY).build(),
                EventDataReservationDenied.builder().reasonCode("BUDGET_EXCEEDED").build(),
                "corr-1", "req-1");

        // Async — wait briefly for the executor to process
        Thread.sleep(200);

        verify(repository).emit(argThat(e ->
                e.getEventType() == EventType.RESERVATION_DENIED &&
                e.getTenantId().equals("t1") &&
                e.getSource().equals("cycles-server") &&
                e.getScope().equals("scope/path") &&
                e.getCorrelationId().equals("corr-1")));
    }

    @Test
    void emit_nullData_works() throws Exception {
        service.emit(EventType.BUDGET_EXHAUSTED, "t1", null, null, null, null, null);

        Thread.sleep(200);

        verify(repository).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_EXHAUSTED &&
                e.getData() == null));
    }

    @Test
    void emit_exceptionInRepo_doesNotThrow() throws Exception {
        doThrow(new RuntimeException("fail")).when(repository).emit(any());

        // Should not throw
        service.emit(EventType.RESERVATION_DENIED, "t1", null, null, null, null, null);

        Thread.sleep(200);
        // No exception propagated — verified by test not failing
    }

    @Test
    void destroy_shutsDownExecutor() {
        // Should not throw
        service.destroy();
    }

    // --- emitBalanceEvents tests ---

    @Test
    void emitBalanceEvents_exhausted_emitsWhenRemainingZero() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, 0L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();

        service.emitBalanceEvents(List.of(b), "t1", actor, null, null);
        Thread.sleep(200);

        verify(repository).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_EXHAUSTED &&
                e.getTenantId().equals("t1") &&
                e.getScope().equals("tenant:t1")));
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
        Thread.sleep(200);

        verify(repository).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_OVER_LIMIT_ENTERED &&
                e.getTenantId().equals("t1") &&
                e.getData() != null));
    }

    @Test
    void emitBalanceEvents_debtIncurred_emitsWhenDebtPositive() throws Exception {
        Balance b = Balance.builder()
                .scope("tenant:t1")
                .scopePath("tenant:t1")
                .remaining(new SignedAmount(Enums.UnitEnum.USD_MICROCENTS, -200L))
                .debt(new Amount(Enums.UnitEnum.USD_MICROCENTS, 200L))
                .overdraftLimit(new Amount(Enums.UnitEnum.USD_MICROCENTS, 1000L))
                .build();
        Actor actor = Actor.builder().type(ActorType.API_KEY).build();

        service.emitBalanceEvents(List.of(b), "t1", actor, null, null);
        Thread.sleep(200);

        verify(repository).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_DEBT_INCURRED &&
                e.getTenantId().equals("t1")));
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
        Thread.sleep(200);

        verify(repository, never()).emit(any());
    }

    @Test
    void emitBalanceEvents_nullOrEmptyBalances_noOp() throws Exception {
        service.emitBalanceEvents(null, "t1", null, null, null);
        service.emitBalanceEvents(List.of(), "t1", null, null, null);
        Thread.sleep(200);

        verify(repository, never()).emit(any());
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
        Thread.sleep(200);

        verify(repository, times(2)).emit(argThat(e ->
                e.getEventType() == EventType.BUDGET_EXHAUSTED));
    }
}
