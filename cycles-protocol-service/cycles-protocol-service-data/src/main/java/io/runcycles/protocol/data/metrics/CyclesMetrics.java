package io.runcycles.protocol.data.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralised Micrometer instrumentation for cycles-server runtime operations.
 *
 * <p>All counters use a dotted namespace ({@code cycles.*}) which Micrometer's
 * Prometheus registry rewrites to underscores ({@code cycles_*}) on scrape. Tag
 * choices prioritise operational signal — "how many denials last 5 minutes, by
 * reason and tenant" — while keeping cardinality bounded: the only high-card tag
 * is {@code tenant}, toggleable via {@code cycles.metrics.tenant-tag.enabled}
 * (default true) for deployments with many thousands of tenants where the
 * per-tenant series count would stress Prometheus.
 *
 * <p>Callers pass {@code null} for unknown tags; we normalise to the sentinel
 * {@code "UNKNOWN"} so Prometheus series names stay stable even when upstream
 * data is sparse. Missing tags would otherwise collapse series, making it look
 * like traffic moved — worse than a flat "unknown" bucket.
 *
 * <p>Not instrumented here (by design):
 *   <ul>
 *     <li>HTTP-layer latency histograms — Spring Boot auto-emits
 *         {@code http.server.requests} with uri/method/status labels already.</li>
 *     <li>Lua-script execution time — EVALSHA timings would mostly duplicate
 *         the HTTP timer for request-synchronous scripts. The expiry-sweep
 *         counter is the exception: it's the only non-request-driven path.</li>
 *   </ul>
 */
@Component
public class CyclesMetrics {

    private final MeterRegistry registry;
    private final boolean tenantTagEnabled;

    public CyclesMetrics(MeterRegistry registry,
                         @Value("${cycles.metrics.tenant-tag.enabled:true}") boolean tenantTagEnabled) {
        this.registry = registry;
        this.tenantTagEnabled = tenantTagEnabled;
    }

    // ---- Reservation lifecycle ----

    /** Emitted on every {@code POST /v1/reservations} outcome. */
    public void recordReserve(String tenant, String decision, String reason, String overagePolicy) {
        registry.counter("cycles.reservations.reserve",
                tags(tenant,
                        "decision", decision,
                        "reason", reason,
                        "overage_policy", overagePolicy))
                .increment();
    }

    /** Emitted on every {@code POST /v1/reservations/{id}/commit} outcome. */
    public void recordCommit(String tenant, String decision, String reason, String overagePolicy) {
        registry.counter("cycles.reservations.commit",
                tags(tenant,
                        "decision", decision,
                        "reason", reason,
                        "overage_policy", overagePolicy))
                .increment();
    }

    /**
     * Emitted on every successful release. {@code actorType} distinguishes
     * tenant-driven releases from v0.1.25.8 admin-on-behalf-of releases.
     */
    public void recordRelease(String tenant, String actorType, String decision, String reason) {
        registry.counter("cycles.reservations.release",
                tags(tenant,
                        "actor_type", actorType,
                        "decision", decision,
                        "reason", reason))
                .increment();
    }

    /** Emitted on every {@code POST /v1/reservations/{id}/extend} outcome. */
    public void recordExtend(String tenant, String decision, String reason) {
        registry.counter("cycles.reservations.extend",
                tags(tenant,
                        "decision", decision,
                        "reason", reason))
                .increment();
    }

    /**
     * Bumped for each reservation the expiry sweep actually marks EXPIRED (the
     * Lua script's "EXPIRED" status). Skipped reservations (still in grace,
     * already finalised) don't increment — the counter tracks real expirations
     * only, not sweep candidates.
     */
    public void recordExpired(String tenant) {
        registry.counter("cycles.reservations.expired",
                tags(tenant))
                .increment();
    }

    // ---- Events ----

    /** Emitted on every {@code POST /v1/events} outcome. */
    public void recordEvent(String tenant, String decision, String reason, String overagePolicy) {
        registry.counter("cycles.events",
                tags(tenant,
                        "decision", decision,
                        "reason", reason,
                        "overage_policy", overagePolicy))
                .increment();
    }

    // ---- Overdraft ----

    /**
     * Bumped on any commit or event that actually accrued non-zero debt. The
     * counter is a unit-free signal ("how often did we go into overdraft") —
     * debt amount is tracked by the balance store, not here, to avoid leaking
     * user-value distributions into metrics.
     */
    public void recordOverdraftIncurred(String tenant) {
        registry.counter("cycles.overdraft.incurred",
                tags(tenant))
                .increment();
    }

    // ---- Internals ----

    private Tags tags(String tenant, String... kvs) {
        List<Tag> list = new ArrayList<>(kvs.length / 2 + 1);
        if (tenantTagEnabled) {
            list.add(Tag.of("tenant", normalise(tenant)));
        }
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            list.add(Tag.of(kvs[i], normalise(kvs[i + 1])));
        }
        return Tags.of(list);
    }

    private static String normalise(String s) {
        return (s == null || s.isBlank()) ? "UNKNOWN" : s;
    }
}
