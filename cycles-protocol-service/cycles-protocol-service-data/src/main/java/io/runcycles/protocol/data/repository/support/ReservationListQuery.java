package io.runcycles.protocol.data.repository.support;

import io.runcycles.protocol.model.ReservationInclude;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable internal contract for reservation listing.
 *
 * <p>The HTTP-facing repository keeps its legacy positional method signature,
 * but all traversal, filtering, sorting, and projection code consumes this
 * value object. That prevents the indexed and authoritative SCAN paths from
 * growing independent parameter lists or filter semantics.
 */
public record ReservationListQuery(
        String tenant,
        String idempotencyKey,
        String status,
        ScopeFilters scopes,
        int limit,
        String startCursor,
        SortOptions sort,
        TimeWindow createdAt,
        TimeWindow expiresAt,
        TimeWindow finalizedAt,
        Set<ReservationInclude> include) {

    public ReservationListQuery {
        scopes = scopes == null ? ScopeFilters.NONE : scopes;
        sort = sort == null ? SortOptions.NONE : sort;
        createdAt = createdAt == null ? TimeWindow.NONE : createdAt;
        expiresAt = expiresAt == null ? TimeWindow.NONE : expiresAt;
        finalizedAt = finalizedAt == null ? TimeWindow.NONE : finalizedAt;
        include = immutableIncludes(include);
    }

    public static Builder builder(String tenant, int limit) {
        return new Builder(tenant, limit);
    }

    public boolean sortRequested() {
        return sort.requested();
    }

    /** Filters that cannot be satisfied by the created-at ZSET score range. */
    public boolean hasPostIndexFilters() {
        return idempotencyKey != null || status != null || scopes.any()
            || expiresAt.active() || finalizedAt.active();
    }

    public String filterHash() {
        return FilterHasher.hash(tenant, idempotencyKey, status,
            scopes.workspace(), scopes.app(), scopes.workflow(), scopes.agent(), scopes.toolset(),
            createdAt.from(), createdAt.to(), expiresAt.from(), expiresAt.to(),
            finalizedAt.from(), finalizedAt.to());
    }

    private static Set<ReservationInclude> immutableIncludes(Set<ReservationInclude> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(source));
    }

    public static final class ScopeFilters {
        public static final ScopeFilters NONE = new ScopeFilters(null, null, null, null, null);

        private final String workspace;
        private final String app;
        private final String workflow;
        private final String agent;
        private final String toolset;
        private final String workspaceSegment;
        private final String appSegment;
        private final String workflowSegment;
        private final String agentSegment;
        private final String toolsetSegment;

        public ScopeFilters(String workspace, String app, String workflow,
                            String agent, String toolset) {
            this.workspace = workspace;
            this.app = app;
            this.workflow = workflow;
            this.agent = agent;
            this.toolset = toolset;
            this.workspaceSegment = segment("workspace", workspace);
            this.appSegment = segment("app", app);
            this.workflowSegment = segment("workflow", workflow);
            this.agentSegment = segment("agent", agent);
            this.toolsetSegment = segment("toolset", toolset);
        }

        public String workspace() { return workspace; }
        public String app() { return app; }
        public String workflow() { return workflow; }
        public String agent() { return agent; }
        public String toolset() { return toolset; }

        public boolean any() {
            return workspace != null || app != null || workflow != null
                || agent != null || toolset != null;
        }

        public boolean matches(String scopePath) {
            String normalized = scopePath == null ? "" : scopePath.toLowerCase();
            return matchesSegment(normalized, workspaceSegment)
                && matchesSegment(normalized, appSegment)
                && matchesSegment(normalized, workflowSegment)
                && matchesSegment(normalized, agentSegment)
                && matchesSegment(normalized, toolsetSegment);
        }

        private static String segment(String kind, String value) {
            return value == null ? null : kind + ":" + value.toLowerCase();
        }

        private static boolean matchesSegment(String scopePath, String segment) {
            if (segment == null) return true;
            int index = scopePath.indexOf(segment);
            if (index < 0) return false;
            int end = index + segment.length();
            boolean startBoundary = index == 0 || scopePath.charAt(index - 1) == '/';
            boolean endBoundary = end == scopePath.length() || scopePath.charAt(end) == '/';
            return startBoundary && endBoundary;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ScopeFilters that)) return false;
            return Objects.equals(workspace, that.workspace)
                && Objects.equals(app, that.app)
                && Objects.equals(workflow, that.workflow)
                && Objects.equals(agent, that.agent)
                && Objects.equals(toolset, that.toolset);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workspace, app, workflow, agent, toolset);
        }

        @Override
        public String toString() {
            return "ScopeFilters[workspace=" + workspace + ", app=" + app
                + ", workflow=" + workflow + ", agent=" + agent
                + ", toolset=" + toolset + "]";
        }
    }

    public record SortOptions(String sortBy, String sortDir) {
        public static final SortOptions NONE = new SortOptions(null, null);

        public boolean requested() {
            return sortBy != null || sortDir != null;
        }
    }

    public record TimeWindow(Long from, Long to) {
        public static final TimeWindow NONE = new TimeWindow(null, null);

        public boolean active() {
            return from != null || to != null;
        }

        public boolean contains(String rawValue) {
            if (!active()) return true;
            if (rawValue == null) return false;
            final long value;
            try {
                value = Long.parseLong(rawValue);
            } catch (NumberFormatException e) {
                return false;
            }
            return (from == null || value >= from) && (to == null || value <= to);
        }
    }

    public static final class Builder {
        private final String tenant;
        private final int limit;
        private String idempotencyKey;
        private String status;
        private ScopeFilters scopes = ScopeFilters.NONE;
        private String startCursor;
        private SortOptions sort = SortOptions.NONE;
        private TimeWindow createdAt = TimeWindow.NONE;
        private TimeWindow expiresAt = TimeWindow.NONE;
        private TimeWindow finalizedAt = TimeWindow.NONE;
        private Set<ReservationInclude> include = Collections.emptySet();

        private Builder(String tenant, int limit) {
            this.tenant = tenant;
            this.limit = limit;
        }

        public Builder idempotencyKey(String value) { this.idempotencyKey = value; return this; }
        public Builder status(String value) { this.status = value; return this; }
        public Builder scopes(String workspace, String app, String workflow,
                              String agent, String toolset) {
            this.scopes = new ScopeFilters(workspace, app, workflow, agent, toolset);
            return this;
        }
        public Builder cursor(String value) { this.startCursor = value; return this; }
        public Builder sort(String sortBy, String sortDir) {
            this.sort = new SortOptions(sortBy, sortDir);
            return this;
        }
        public Builder createdAt(Long from, Long to) {
            this.createdAt = new TimeWindow(from, to);
            return this;
        }
        public Builder expiresAt(Long from, Long to) {
            this.expiresAt = new TimeWindow(from, to);
            return this;
        }
        public Builder finalizedAt(Long from, Long to) {
            this.finalizedAt = new TimeWindow(from, to);
            return this;
        }
        public Builder include(Set<ReservationInclude> value) { this.include = value; return this; }

        public ReservationListQuery build() {
            return new ReservationListQuery(tenant, idempotencyKey, status, scopes, limit,
                startCursor, sort, createdAt, expiresAt, finalizedAt, include);
        }
    }
}
