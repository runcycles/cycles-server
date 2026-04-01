package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.model.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
