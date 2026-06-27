package io.runcycles.protocol.data.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.data.util.LogSanitizer;
import io.runcycles.protocol.data.util.TraceContext;
import io.runcycles.protocol.model.Balance;
import io.runcycles.protocol.model.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async event emission for runtime controllers.
 * Emits on a dedicated thread pool so the request thread returns immediately.
 * All failures are logged but never propagated to callers.
 */
@Service
public class EventEmitterService implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(EventEmitterService.class);
    private static final String SOURCE = "cycles-server";
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    private final ThreadPoolExecutor emitExecutor;

    @Autowired private EventEmitterRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @Autowired
    public EventEmitterService(
            @Value("${cycles.events.emit.threads:0}") int configuredThreads,
            @Value("${cycles.events.emit.queue-capacity:" + DEFAULT_QUEUE_CAPACITY + "}") int queueCapacity) {
        this.emitExecutor = buildExecutor(configuredThreads, queueCapacity);
    }

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
        emit(type, tenantId, scope, actor, eventData, correlationId, requestId, TraceContext.EMPTY);
    }

    public void emit(EventType type, String tenantId, String scope, Actor actor,
                     Object eventData, String correlationId, String requestId, TraceContext trace) {
        TraceContext traceNonNull = trace != null ? trace : TraceContext.EMPTY;
        try {
            emitExecutor.execute(() -> {
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
                            .traceId(traceNonNull.traceId())
                            .traceFlags(traceNonNull.traceFlags())
                            .traceparentInboundValid(traceNonNull.traceparentInboundValid())
                            .build();
                    repository.emit(event);
                } catch (Exception e) {
                    LOG.error("Failed to emit event: event_type={} tenant={} scope={} correlation_id={} request_id={} trace_id={} actor_type={} error={}",
                            type, LogSanitizer.sanitize(tenantId), LogSanitizer.sanitize(scope), correlationId, requestId, traceNonNull.traceId(),
                            actor != null ? actor.getType() : null, LogSanitizer.sanitize(e.toString()), e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("Event emission queue saturated; dropping non-blocking event: event_type={} tenant={} scope={} correlation_id={} request_id={} trace_id={} actor_type={} active_threads={} queued={} queue_capacity={}",
                    type, LogSanitizer.sanitize(tenantId), LogSanitizer.sanitize(scope), correlationId, requestId,
                    traceNonNull.traceId(), actor != null ? actor.getType() : null,
                    emitExecutor.getActiveCount(), emitExecutor.getQueue().size(),
                    emitExecutor.getQueue().remainingCapacity() + emitExecutor.getQueue().size());
        }
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
        emitBalanceEvents(balances, tenantId, actor, null, null, null,
                null, null, correlationId, requestId, TraceContext.EMPTY);
    }

    /**
     * Overload accepting reservation context for richer event data on debt_incurred events.
     */
    public void emitBalanceEvents(List<Balance> balances, String tenantId, Actor actor,
                                  String reservationId, String overagePolicy,
                                  Map<String, Long> scopeDebtIncurred,
                                  String correlationId, String requestId) {
        emitBalanceEvents(balances, tenantId, actor, reservationId, overagePolicy, scopeDebtIncurred,
                null, null, correlationId, requestId, TraceContext.EMPTY);
    }

    /**
     * Overload with pre-mutation state maps; trace context defaults to empty (deprecated path).
     */
    public void emitBalanceEvents(List<Balance> balances, String tenantId, Actor actor,
                                  String reservationId, String overagePolicy,
                                  Map<String, Long> scopeDebtIncurred,
                                  Map<String, Long> preRemaining,
                                  Map<String, Boolean> preIsOverLimit,
                                  String correlationId, String requestId) {
        emitBalanceEvents(balances, tenantId, actor, reservationId, overagePolicy, scopeDebtIncurred,
                preRemaining, preIsOverLimit, correlationId, requestId, TraceContext.EMPTY);
    }

    /**
     * Full overload with pre-mutation state maps for transition-based event emission.
     * Only emits budget state events on state transitions (not on every operation where
     * the post-state matches). Fixes duplicate event firing (cycles-server-events#15).
     */
    public void emitBalanceEvents(List<Balance> balances, String tenantId, Actor actor,
                                  String reservationId, String overagePolicy,
                                  Map<String, Long> scopeDebtIncurred,
                                  Map<String, Long> preRemaining,
                                  Map<String, Boolean> preIsOverLimit,
                                  String correlationId, String requestId, TraceContext trace) {
        Map<String, Long> debtMap = scopeDebtIncurred != null ? scopeDebtIncurred : Collections.emptyMap();
        Map<String, Long> preRem = preRemaining != null ? preRemaining : Collections.emptyMap();
        Map<String, Boolean> preOvl = preIsOverLimit != null ? preIsOverLimit : Collections.emptyMap();
        if (balances == null || balances.isEmpty()) return;
        for (Balance b : balances) {
            String scopePath = b.getScopePath();
            String unit = b.getRemaining() != null ? b.getRemaining().getUnit().name() : null;
            long preRemainingVal = preRem.getOrDefault(scopePath, Long.MAX_VALUE);
            boolean preOverLimitVal = preOvl.getOrDefault(scopePath, false);
            // budget.exhausted — transition: pre_remaining > 0 && remaining == 0
            if (b.getRemaining() != null && b.getRemaining().getAmount() != null
                    && b.getRemaining().getAmount() == 0L && preRemainingVal > 0L) {
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
                        correlationId, requestId, trace);
            }
            // budget.over_limit_entered — transition: pre=false && post=true
            if (Boolean.TRUE.equals(b.getIsOverLimit()) && !preOverLimitVal) {
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
                        correlationId, requestId, trace);
            }
            // budget.debt_incurred — only when new debt was created in this operation
            Long perScopeDebt = debtMap.get(scopePath);
            if (perScopeDebt != null && perScopeDebt > 0L
                    && b.getDebt() != null && b.getDebt().getAmount() != null) {
                Long odLimit = b.getOverdraftLimit() != null ? b.getOverdraftLimit().getAmount() : null;
                emit(EventType.BUDGET_DEBT_INCURRED, tenantId, b.getScopePath(),
                        actor,
                        EventDataBudgetDebtIncurred.builder()
                                .scope(b.getScopePath())
                                .unit(b.getDebt().getUnit().name())
                                .reservationId(reservationId)
                                .debtIncurred(perScopeDebt)
                                .totalDebt(b.getDebt().getAmount())
                                .overdraftLimit(odLimit)
                                .overagePolicy(overagePolicy)
                                .build(),
                        correlationId, requestId, trace);
            }
        }
    }

    private static ThreadPoolExecutor buildExecutor(int configuredThreads, int queueCapacity) {
        int threads = configuredThreads > 0
                ? configuredThreads
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                eventThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory eventThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "event-emit-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
