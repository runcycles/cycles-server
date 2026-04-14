package io.runcycles.protocol.data.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link CyclesMetrics} component. Uses a {@link SimpleMeterRegistry}
 * so assertions read directly off the counters without Prometheus-specific shaping.
 *
 * <p>Each {@code record*} method is tested for three things: (1) the right counter name
 * is registered, (2) every documented tag ends up on the counter with the value we
 * passed, (3) null/blank tag values normalise to the stable sentinel {@code "UNKNOWN"}
 * so Prometheus series names don't collapse or disappear when upstream data is sparse.
 */
class CyclesMetricsTest {

    private MeterRegistry registry;
    private CyclesMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CyclesMetrics(registry, true);
    }

    private double countOf(String name, String... kvs) {
        var search = registry.find(name);
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            search = search.tag(kvs[i], kvs[i + 1]);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    @Nested
    @DisplayName("tag set and increment semantics")
    class TagsAndIncrement {

        @Test
        void recordReserveTagsAllFourDimensions() {
            metrics.recordReserve("tenant-a", "ALLOW", "OK", "REJECT");
            metrics.recordReserve("tenant-a", "ALLOW", "OK", "REJECT");
            assertThat(countOf("cycles.reservations.reserve",
                    "tenant", "tenant-a",
                    "decision", "ALLOW",
                    "reason", "OK",
                    "overage_policy", "REJECT"))
                    .isEqualTo(2.0);
        }

        @Test
        void recordCommitTagsAllFourDimensions() {
            metrics.recordCommit("tenant-a", "COMMITTED", "OK", "ALLOW_WITH_OVERDRAFT");
            assertThat(countOf("cycles.reservations.commit",
                    "tenant", "tenant-a",
                    "decision", "COMMITTED",
                    "overage_policy", "ALLOW_WITH_OVERDRAFT"))
                    .isEqualTo(1.0);
        }

        @Test
        void recordReleaseSeparatesActorType() {
            metrics.recordRelease("tenant-a", "tenant", "RELEASED", "OK");
            metrics.recordRelease("tenant-a", "admin_on_behalf_of", "RELEASED", "OK");
            assertThat(countOf("cycles.reservations.release",
                    "tenant", "tenant-a", "actor_type", "tenant"))
                    .isEqualTo(1.0);
            assertThat(countOf("cycles.reservations.release",
                    "tenant", "tenant-a", "actor_type", "admin_on_behalf_of"))
                    .isEqualTo(1.0);
        }

        @Test
        void recordExtendTagsDecisionAndReason() {
            metrics.recordExtend("tenant-a", "ACTIVE", "OK");
            metrics.recordExtend("tenant-a", "DENY", "RESERVATION_EXPIRED");
            assertThat(countOf("cycles.reservations.extend",
                    "decision", "ACTIVE"))
                    .isEqualTo(1.0);
            assertThat(countOf("cycles.reservations.extend",
                    "decision", "DENY", "reason", "RESERVATION_EXPIRED"))
                    .isEqualTo(1.0);
        }

        @Test
        void recordExpiredTagsTenant() {
            metrics.recordExpired("tenant-a");
            metrics.recordExpired("tenant-a");
            metrics.recordExpired("tenant-b");
            assertThat(countOf("cycles.reservations.expired", "tenant", "tenant-a"))
                    .isEqualTo(2.0);
            assertThat(countOf("cycles.reservations.expired", "tenant", "tenant-b"))
                    .isEqualTo(1.0);
        }

        @Test
        void recordEventTagsAllFourDimensions() {
            metrics.recordEvent("tenant-a", "APPLIED", "OK", "ALLOW_IF_AVAILABLE");
            assertThat(countOf("cycles.events",
                    "tenant", "tenant-a",
                    "decision", "APPLIED",
                    "overage_policy", "ALLOW_IF_AVAILABLE"))
                    .isEqualTo(1.0);
        }

        @Test
        void recordOverdraftIncurredTagsTenant() {
            metrics.recordOverdraftIncurred("tenant-a");
            assertThat(countOf("cycles.overdraft.incurred", "tenant", "tenant-a"))
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("null normalisation to UNKNOWN sentinel")
    class NullNormalisation {

        @Test
        void nullTenantNormalisesToUnknown() {
            metrics.recordReserve(null, "ALLOW", "OK", "REJECT");
            assertThat(countOf("cycles.reservations.reserve", "tenant", "UNKNOWN"))
                    .isEqualTo(1.0);
        }

        @Test
        void blankValuesNormaliseToUnknown() {
            metrics.recordReserve("tenant-a", "", "  ", null);
            assertThat(countOf("cycles.reservations.reserve",
                    "decision", "UNKNOWN", "reason", "UNKNOWN", "overage_policy", "UNKNOWN"))
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("tenant-tag.enabled=false")
    class TenantTagDisabled {

        @Test
        void omitsTenantTagForHighCardinalityDeployments() {
            CyclesMetrics noTenant = new CyclesMetrics(registry, false);
            noTenant.recordReserve("tenant-a", "ALLOW", "OK", "REJECT");
            // The tenant tag should NOT be present on the registered counter.
            var counters = registry.find("cycles.reservations.reserve").counters();
            assertThat(counters).hasSize(1);
            assertThat(counters.iterator().next().getId().getTag("tenant"))
                    .as("tenant tag must be absent when tenant-tag.enabled=false")
                    .isNull();
        }
    }
}
