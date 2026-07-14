package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.repository.support.FilterHasher;
import io.runcycles.protocol.data.repository.support.ReservationListQuery;
import io.runcycles.protocol.model.ReservationInclude;
import io.runcycles.protocol.model.ReservationSummary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationQueryBoundaryTest {

    @Test
    void querySnapshotsIncludesAndOwnsTheLegacyFilterHashContract() {
        EnumSet<ReservationInclude> requested = EnumSet.of(ReservationInclude.METADATA);
        ReservationListQuery query = ReservationListQuery.builder("acme", 20)
            .idempotencyKey("idem-1")
            .status("COMMITTED")
            .scopes("dev", "chat", "wf", "agent", "tools")
            .cursor("cursor")
            .sort("created_at_ms", "desc")
            .createdAt(10L, 20L)
            .expiresAt(30L, 40L)
            .finalizedAt(50L, 60L)
            .include(requested)
            .build();

        requested.add(ReservationInclude.EVIDENCE);

        assertThat(query.include()).containsExactly(ReservationInclude.METADATA);
        assertThatThrownBy(() -> query.include().add(ReservationInclude.EVIDENCE))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(query.filterHash()).isEqualTo(FilterHasher.hash(
            "acme", "idem-1", "COMMITTED", "dev", "chat", "wf", "agent", "tools",
            10L, 20L, 30L, 40L, 50L, 60L));
        assertThat(query.hasPostIndexFilters()).isTrue();
        assertThat(query.sortRequested()).isTrue();
        assertThat(query.scopes()).isEqualTo(new ReservationListQuery.ScopeFilters(
            "dev", "chat", "wf", "agent", "tools"));
        assertThat(query.scopes().hashCode()).isEqualTo(new ReservationListQuery.ScopeFilters(
            "dev", "chat", "wf", "agent", "tools").hashCode());
        assertThat(query.scopes().toString()).contains("workspace=dev", "toolset=tools");
    }

    @Test
    void scopeAndTimePredicatesKeepExactBoundarySemantics() {
        ReservationListQuery.ScopeFilters scopes = new ReservationListQuery.ScopeFilters(
            "Dev", "chat", null, null, null);

        assertThat(scopes.matches("tenant:acme/workspace:dev/app:chat")).isTrue();
        assertThat(scopes.matches("tenant:acme/workspace:devops/app:chat")).isFalse();
        assertThat(scopes.matches("tenant:acme/workspace:dev/app:chat-plus")).isFalse();

        ReservationListQuery.TimeWindow window =
            new ReservationListQuery.TimeWindow(100L, 200L);
        assertThat(window.contains("100")).isTrue();
        assertThat(window.contains("200")).isTrue();
        assertThat(window.contains("99")).isFalse();
        assertThat(window.contains("not-a-number")).isFalse();
        assertThat(window.contains(null)).isFalse();
    }

    @Test
    void canonicalMapperAppliesEveryFilterAndProjection() throws Exception {
        ReservationHashMapper mapper = new ReservationHashMapper(new ObjectMapper());
        ReservationListQuery query = ReservationListQuery.builder("acme", 10)
            .idempotencyKey("idem-1")
            .status("COMMITTED")
            .scopes("dev", "chat", null, null, null)
            .createdAt(1_000L, 1_000L)
            .expiresAt(2_000L, 2_000L)
            .finalizedAt(1_500L, 1_500L)
            .include(Set.of(ReservationInclude.METADATA))
            .build();
        Map<String, String> fields = validFields();

        ReservationSummary summary = mapper.matchingSummary(fields, query);

        assertThat(summary).isNotNull();
        assertThat(summary.getReservationId()).isEqualTo("r1");
        assertThat(summary.getMetadata()).containsEntry("source", "test");
        assertThat(summary.getEvidence()).isNull();

        Map<String, String> wrongScope = new HashMap<>(fields);
        wrongScope.put("scope_path", "tenant:acme/workspace:devops/app:chat");
        assertThat(mapper.matchingSummary(wrongScope, query)).isNull();

        Map<String, String> wrongFinalizedAt = new HashMap<>(fields);
        wrongFinalizedAt.put("committed_at", "1499");
        assertThat(mapper.matchingSummary(wrongFinalizedAt, query)).isNull();
    }

    @Test
    void facadeKeepsOnlyPublicListEntryPointsAndTypedComponentOwnsExecution() {
        List<java.lang.reflect.Method> facadeMethods = List.of(
                RedisReservationRepository.class.getDeclaredMethods()).stream()
            .filter(method -> method.getName().startsWith("listReservations"))
            .toList();

        assertThat(facadeMethods).hasSize(2);
        assertThat(facadeMethods).allMatch(method -> Modifier.isPublic(method.getModifiers()));
        assertThat(RedisReservationQueryRepository.class.getDeclaredMethods())
            .anyMatch(method -> method.getName().equals("list")
                && method.getParameterTypes()[0] == ReservationListQuery.class);
    }

    @Test
    void projectionNeverHydratesReplaySnapshotsAndDetailArrayIsDefensive() {
        assertThat(ReservationHashMapper.projection(
            EnumSet.allOf(ReservationInclude.class), true))
            .noneMatch(field -> field.endsWith("_response_json"));

        ReservationHashMapper mapper = new ReservationHashMapper(new ObjectMapper());
        String[] first = mapper.detailFieldArray();
        String original = first[0];
        first[0] = "mutated";
        assertThat(mapper.detailFieldArray()[0]).isEqualTo(original);
    }

    private static Map<String, String> validFields() {
        return new HashMap<>(Map.ofEntries(
            Map.entry("reservation_id", "r1"),
            Map.entry("tenant", "acme"),
            Map.entry("state", "COMMITTED"),
            Map.entry("subject_json", "{\"tenant\":\"acme\",\"app\":\"chat\"}"),
            Map.entry("action_json", "{\"kind\":\"llm\",\"name\":\"chat\"}"),
            Map.entry("estimate_amount", "100"),
            Map.entry("estimate_unit", "TOKENS"),
            Map.entry("scope_path", "tenant:acme/workspace:dev/app:chat"),
            Map.entry("affected_scopes", "[\"tenant:acme\",\"tenant:acme/workspace:dev/app:chat\"]"),
            Map.entry("created_at", "1000"),
            Map.entry("expires_at", "2000"),
            Map.entry("committed_at", "1500"),
            Map.entry("charged_amount", "80"),
            Map.entry("idempotency_key", "idem-1"),
            Map.entry("metadata_json", "{\"source\":\"test\"}")));
    }
}
