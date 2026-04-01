package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Async event emission for runtime controllers.
 * Emits on a dedicated thread pool so the request thread returns immediately.
 * All failures are logged but never propagated to callers.
 */
@Service
public class EventEmitterService implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(EventEmitterService.class);
    private static final String SOURCE = "cycles-server";

    private final ExecutorService emitExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4),
            r -> { Thread t = new Thread(r, "event-emit"); t.setDaemon(true); return t; });

    @Autowired private EventEmitterRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @Override
    public void destroy() {
        emitExecutor.shutdown();
        try {
            if (!emitExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                emitExecutor.shutdownNow();
                LOG.warn("Event emitter executor did not terminate within 30s");
            }
        } catch (InterruptedException e) {
            emitExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void emit(EventType type, String tenantId, String scope, Actor actor,
                     Object eventData, String correlationId, String requestId) {
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> data = eventData != null
                        ? objectMapper.convertValue(eventData, new com.fasterxml.jackson.core.type.TypeReference<>() {})
                        : null;
                Event event = Event.builder()
                        .eventType(type)
                        .category(type.getCategory())
                        .tenantId(tenantId)
                        .scope(scope)
                        .source(SOURCE)
                        .actor(actor)
                        .data(data)
                        .correlationId(correlationId)
                        .requestId(requestId)
                        .build();
                repository.emit(event);
            } catch (Exception e) {
                LOG.error("Failed to emit event {}: {}", type, e.getMessage());
            }
        }, emitExecutor);
    }
}
