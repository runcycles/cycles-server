package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.model.Balance;
import io.runcycles.protocol.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
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

    /**
     * Inspect post-operation balances and emit budget state events:
     * - budget.exhausted: remaining == 0 on any scope
     * - budget.over_limit_entered: is_over_limit == true on any scope
     * - budget.debt_incurred: debt > 0 on any scope
     *
     * Fire-and-forget, same as all event emission. Callers pass balances
     * returned from Lua scripts — no extra Redis calls needed.
     */
    public void emitBalanceEvents(List<Balance> balances, String tenantId, Actor actor,
                                  String correlationId, String requestId) {
        emitBalanceEvents(balances, tenantId, actor, null, null, correlationId, requestId);
    }

    /**
     * Overload accepting reservation context for richer event data on debt_incurred events.
     */
    public void emitBalanceEvents(List<Balance> balances, String tenantId, Actor actor,
                                  String reservationId, String overagePolicy,
                                  String correlationId, String requestId) {
        if (balances == null || balances.isEmpty()) return;
        for (Balance b : balances) {
            String unit = b.getRemaining() != null ? b.getRemaining().getUnit().name() : null;
            // budget.exhausted — remaining.amount == 0
            if (b.getRemaining() != null && b.getRemaining().getAmount() != null
                    && b.getRemaining().getAmount() == 0L) {
                // Spec: use EventDataBudgetThreshold with threshold=1.0, direction=rising
                Double utilization = null;
                Long allocated = b.getAllocated() != null ? b.getAllocated().getAmount() : null;
                Long spent = b.getSpent() != null ? b.getSpent().getAmount() : null;
                Long reserved = b.getReserved() != null ? b.getReserved().getAmount() : null;
                if (allocated != null && allocated > 0) {
                    long used = (spent != null ? spent : 0L) + (reserved != null ? reserved : 0L);
                    utilization = (double) used / allocated;
                }
                emit(EventType.BUDGET_EXHAUSTED, tenantId, b.getScopePath(),
                        actor,
                        EventDataBudgetThreshold.builder()
                                .scope(b.getScopePath())
                                .unit(unit)
                                .threshold(1.0)
                                .utilization(utilization)
                                .allocated(allocated)
                                .remaining(0L)
                                .spent(spent)
                                .reserved(reserved)
                                .direction("rising")
                                .build(),
                        correlationId, requestId);
            }
            // budget.over_limit_entered — is_over_limit flipped to true
            if (Boolean.TRUE.equals(b.getIsOverLimit())) {
                Long debt = b.getDebt() != null ? b.getDebt().getAmount() : null;
                Long odLimit = b.getOverdraftLimit() != null ? b.getOverdraftLimit().getAmount() : null;
                Double debtUtilization = (debt != null && odLimit != null && odLimit > 0)
                        ? (double) debt / odLimit : null;
                emit(EventType.BUDGET_OVER_LIMIT_ENTERED, tenantId, b.getScopePath(),
                        actor,
                        EventDataBudgetOverLimit.builder()
                                .scope(b.getScopePath())
                                .unit(unit)
                                .debt(debt)
                                .overdraftLimit(odLimit)
                                .isOverLimit(true)
                                .debtUtilization(debtUtilization)
                                .build(),
                        correlationId, requestId);
            }
            // budget.debt_incurred — debt > 0
            if (b.getDebt() != null && b.getDebt().getAmount() != null
                    && b.getDebt().getAmount() > 0L) {
                Long odLimit = b.getOverdraftLimit() != null ? b.getOverdraftLimit().getAmount() : null;
                emit(EventType.BUDGET_DEBT_INCURRED, tenantId, b.getScopePath(),
                        actor,
                        EventDataBudgetDebtIncurred.builder()
                                .scope(b.getScopePath())
                                .unit(b.getDebt().getUnit().name())
                                .reservationId(reservationId)
                                .totalDebt(b.getDebt().getAmount())
                                .overdraftLimit(odLimit)
                                .overagePolicy(overagePolicy)
                                .build(),
                        correlationId, requestId);
            }
        }
    }
}
