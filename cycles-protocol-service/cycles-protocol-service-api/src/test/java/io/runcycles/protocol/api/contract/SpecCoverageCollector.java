package io.runcycles.protocol.api.contract;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-singleton set recording {@code "METHOD /path/template"} pairs for every
 * spec-path request validated during a test run. Populated by
 * {@link ContractValidationConfig}'s MockMvc matcher and by
 * {@link ContractValidatingRestTemplateInterceptor} for integration tests;
 * consumed by {@code SpecCoverageReportTest} (in the {@code zzz} sub-package
 * so it sorts last under {@code <runOrder>alphabetical</runOrder>}).
 *
 * <p>Stores templates (e.g. {@code GET /v1/reservations/{reservation_id}}),
 * not concrete URIs, so the set size tops out at N (one per spec operation)
 * regardless of how many fixtures tests use.
 *
 * <p>Surefire runs tests in a single JVM by default; a static collection is
 * sufficient. If the build ever switches to {@code <forkCount>} greater than
 * 1, this needs to be file-backed instead.
 */
public final class SpecCoverageCollector {

    private static final Set<String> COVERED = ConcurrentHashMap.newKeySet();

    private SpecCoverageCollector() {}

    /** Record that a request hit the given method + spec path template. */
    public static void record(String method, String pathTemplate) {
        COVERED.add(method.toUpperCase() + " " + pathTemplate);
    }

    /** Snapshot of all covered {@code "METHOD /path/template"} keys. */
    public static Set<String> covered() {
        return Set.copyOf(COVERED);
    }
}
