package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Non-blocking event emission for runtime controllers.
 * All failures are logged but never propagated to callers.
 */
@Service
public class EventEmitterService {

    private static final Logger LOG = LoggerFactory.getLogger(EventEmitterService.class);
    private static final String SOURCE = "cycles-server";

    @Autowired private EventEmitterRepository repository;
    @Autowired private ObjectMapper objectMapper;

    public void emit(EventType type, String tenantId, String scope, Actor actor,
                     Object eventData, String correlationId, String requestId) {
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
    }
}
