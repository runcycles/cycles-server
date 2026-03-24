package io.runcycles.protocol.data.service;

import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.Subject;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;

/** Cycles Protocol v0.1.24 */
@Service
public class ScopeDerivationService {

    private static final String BUDGET_PREFIX = "budget:";

    private record ScopeLevel(String name, Function<Subject, String> extractor) {}

    private static final List<ScopeLevel> HIERARCHY = List.of(
            new ScopeLevel("tenant",    Subject::getTenant),
            new ScopeLevel("workspace", Subject::getWorkspace),
            new ScopeLevel("app",       Subject::getApp),
            new ScopeLevel("workflow",  Subject::getWorkflow),
            new ScopeLevel("agent",     Subject::getAgent),
            new ScopeLevel("toolset",   Subject::getToolset)
    );


    /**
     * Returns the full canonical scope path as a single string.
     * Only explicitly provided subject levels are included (gaps are skipped).
     * Example: {tenant:acme, agent:summarizer-v2} → "tenant:acme/agent:summarizer-v2"
     */
    public String buildScopePath(@NonNull Subject subject) {
        List<String> segments = buildSegments(subject);
        return String.join("/", segments);
    }


    /**
     * Returns a list of cumulative ancestor scope paths, one per explicitly provided level.
     * Gaps are skipped — only levels present in the subject are included.
     * Example: {tenant:acme, agent:summarizer-v2} → [
     *   "tenant:acme",
     *   "tenant:acme/agent:summarizer-v2"
     * ]
     */
    public List<String> deriveScopes(@NonNull Subject subject) {
        List<String> segments = buildSegments(subject);
        List<String> scopes = new ArrayList<>();
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (!path.isEmpty()) path.append("/");
            path.append(segment);
            scopes.add(path.toString());
        }
        return scopes;
    }
    /**
     * Builds a Redis budget key for the given subject and unit.
     * Example: budget:tenant:acme/agent:summarizer-v2:USD_MICROCENTS
     */
    public String buildBudgetKey(@NonNull Subject subject, @NonNull Enums.UnitEnum unit) {
        return BUDGET_PREFIX + buildScopePath(subject) + ":" + unit.name();
    }
    /**
     * Core: derives ordered path segments for explicitly provided subject levels only.
     * Levels not present in the subject are skipped (no gap-filling with "default").
     * Example: {tenant:acme, agent:summarizer-v2} → ["tenant:acme", "agent:summarizer-v2"]
     */
    private List<String> buildSegments(Subject subject) {
        List<String> segments = new ArrayList<>();
        for (ScopeLevel level : HIERARCHY) {
            String value = level.extractor().apply(subject);
            if (value != null && !value.isBlank()) {
                segments.add(level.name() + ":" + value.toLowerCase());
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Subject must have at least one scope level defined");
        }
        return segments;
    }
}
