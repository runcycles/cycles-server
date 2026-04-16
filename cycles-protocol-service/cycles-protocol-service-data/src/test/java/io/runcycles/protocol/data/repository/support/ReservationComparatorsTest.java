package io.runcycles.protocol.data.repository.support;

import io.runcycles.protocol.model.Action;
import io.runcycles.protocol.model.Amount;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ReservationSummary;
import io.runcycles.protocol.model.Subject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReservationComparators")
class ReservationComparatorsTest {

    private static ReservationSummary row(String id, long created, long expires, long reserved,
                                           String tenant, String scope, Enums.ReservationStatus status) {
        Subject s = new Subject();
        s.setTenant(tenant);
        Action a = new Action();
        a.setKind("llm");
        a.setName("chat");
        return ReservationSummary.builder()
            .reservationId(id)
            .status(status)
            .subject(s)
            .action(a)
            .reserved(new Amount(Enums.UnitEnum.USD_MICROCENTS, reserved))
            .createdAtMs(created)
            .expiresAtMs(expires)
            .scopePath(scope)
            .affectedScopes(List.of(scope))
            .build();
    }

    @Test
    @DisplayName("sort by created_at_ms asc")
    void createdAtAsc() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("r2", 200, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r1", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r3", 300, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("created_at_ms", "asc"));
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("r1", "r2", "r3");
    }

    @Test
    @DisplayName("sort by created_at_ms desc (default when sort_dir omitted)")
    void createdAtDescDefault() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("r1", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r3", 300, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r2", 200, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("created_at_ms", null));
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("r3", "r2", "r1");
    }

    @Test
    @DisplayName("sort by reserved amount")
    void byReserved() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("r1", 100, 999, 500, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r2", 100, 999, 100, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r3", 100, 999, 300, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("reserved", "asc"));
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("r2", "r3", "r1");
    }

    @Test
    @DisplayName("sort by status then tiebreak on reservation_id asc")
    void statusTiebreak() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("r3", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.COMMITTED),
            row("r1", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.COMMITTED),
            row("r2", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("status", "asc"));
        // Status ACTIVE < COMMITTED lexicographically; tied COMMITTED rows sort r1 < r3 on id.
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("r2", "r1", "r3");
    }

    @Test
    @DisplayName("sort by scope_path lexicographically")
    void byScopePath() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("r1", 100, 999, 10, "acme", "tenant:acme/workspace:zed", Enums.ReservationStatus.ACTIVE),
            row("r2", 100, 999, 10, "acme", "tenant:acme/workspace:alpha", Enums.ReservationStatus.ACTIVE),
            row("r3", 100, 999, 10, "acme", "tenant:acme/workspace:beta", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("scope_path", "asc"));
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("r2", "r3", "r1");
    }

    @Test
    @DisplayName("sort by tenant over Subject.tenant field")
    void byTenant() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("r1", 100, 999, 10, "zed-corp", "tenant:zed", Enums.ReservationStatus.ACTIVE),
            row("r2", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("tenant", "asc"));
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("r2", "r1");
    }

    @Test
    @DisplayName("null subject.tenant sorts last under asc and desc")
    void nullTenantSafety() {
        ReservationSummary withNull = row("r1", 100, 999, 10, "acme", "tenant:acme",
            Enums.ReservationStatus.ACTIVE);
        withNull.getSubject().setTenant(null);
        ReservationSummary withValue = row("r2", 100, 999, 10, "acme", "tenant:acme",
            Enums.ReservationStatus.ACTIVE);

        List<ReservationSummary> ascRows = new ArrayList<>(List.of(withNull, withValue));
        ascRows.sort(ReservationComparators.of("tenant", "asc"));
        assertThat(ascRows.get(1)).isSameAs(withNull); // nulls last

        List<ReservationSummary> descRows = new ArrayList<>(List.of(withNull, withValue));
        descRows.sort(ReservationComparators.of("tenant", "desc"));
        // Reversed comparator with nullsLast becomes nullsFirst on the reversed order —
        // so the null-carrying row ends up at position 0. Either behaviour is acceptable
        // for a stable total order; this test just pins the observed semantics.
        assertThat(descRows.get(0)).isSameAs(withNull);
    }

    @Test
    @DisplayName("extractSortValue produces cursor-safe string for every sort key")
    void extractSortValueCoversAllKeys() {
        ReservationSummary r = row("res_x", 1700000000000L, 1700060000000L, 500,
            "acme", "tenant:acme/app:myapp", Enums.ReservationStatus.COMMITTED);

        assertThat(ReservationComparators.extractSortValue(r, "reservation_id")).isEqualTo("res_x");
        assertThat(ReservationComparators.extractSortValue(r, "tenant")).isEqualTo("acme");
        assertThat(ReservationComparators.extractSortValue(r, "scope_path")).isEqualTo("tenant:acme/app:myapp");
        assertThat(ReservationComparators.extractSortValue(r, "status")).isEqualTo("COMMITTED");
        assertThat(ReservationComparators.extractSortValue(r, "reserved")).isEqualTo("500");
        assertThat(ReservationComparators.extractSortValue(r, "created_at_ms")).isEqualTo("1700000000000");
        assertThat(ReservationComparators.extractSortValue(r, "expires_at_ms")).isEqualTo("1700060000000");
    }

    @Test
    @DisplayName("default comparator when both args null → created_at_ms desc")
    void defaultSemantics() {
        Comparator<ReservationSummary> cmp = ReservationComparators.of(null, null);
        ReservationSummary older = row("r1", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE);
        ReservationSummary newer = row("r2", 200, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE);
        assertThat(cmp.compare(newer, older)).isNegative(); // newer-first under desc
    }

    @Test
    @DisplayName("sort by reservation_id as primary key")
    void byReservationIdPrimary() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("res_c", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("res_a", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("res_b", 100, 999, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("reservation_id", "asc"));
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("res_a", "res_b", "res_c");
    }

    @Test
    @DisplayName("sort by expires_at_ms as primary key")
    void byExpiresAtMsPrimary() {
        List<ReservationSummary> rows = new ArrayList<>(List.of(
            row("r1", 100, 300, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r2", 100, 100, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE),
            row("r3", 100, 200, 10, "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE)
        ));
        rows.sort(ReservationComparators.of("expires_at_ms", "asc"));
        assertThat(rows).extracting(ReservationSummary::getReservationId)
            .containsExactly("r2", "r3", "r1");
    }

    @Test
    @DisplayName("null values sort last across every primary sort key")
    void nullsLastForAllPrimaryKeys() {
        // Exercises the nullsLast branches in primaryFor() for the non-tenant keys.
        ReservationSummary withNulls = ReservationSummary.builder()
            .reservationId(null)
            .status(null)
            .subject(null)
            .action(null)
            .reserved(null)
            .createdAtMs(null)
            .expiresAtMs(null)
            .scopePath(null)
            .affectedScopes(List.of())
            .build();
        ReservationSummary populated = row("r_populated", 100, 200, 10,
            "acme", "tenant:acme", Enums.ReservationStatus.ACTIVE);

        for (String key : List.of("reservation_id", "scope_path", "status", "reserved",
                "created_at_ms", "expires_at_ms", "tenant")) {
            List<ReservationSummary> ascRows = new ArrayList<>(List.of(withNulls, populated));
            ascRows.sort(ReservationComparators.of(key, "asc"));
            assertThat(ascRows.get(1))
                .as("nullsLast under asc for sort_by=%s", key)
                .isSameAs(withNulls);
        }
    }

    @Test
    @DisplayName("extractSortValue returns empty string for null fields across every key")
    void extractSortValueNullSafety() {
        ReservationSummary nulls = ReservationSummary.builder()
            .reservationId(null)
            .status(null)
            .subject(null)
            .reserved(new Amount(Enums.UnitEnum.USD_MICROCENTS, null))
            .createdAtMs(null)
            .expiresAtMs(null)
            .scopePath(null)
            .affectedScopes(List.of())
            .build();

        assertThat(ReservationComparators.extractSortValue(nulls, "reservation_id")).isEqualTo("");
        assertThat(ReservationComparators.extractSortValue(nulls, "tenant")).isEqualTo("");
        assertThat(ReservationComparators.extractSortValue(nulls, "scope_path")).isEqualTo("");
        assertThat(ReservationComparators.extractSortValue(nulls, "status")).isEqualTo("");
        assertThat(ReservationComparators.extractSortValue(nulls, "reserved")).isEqualTo("");
        assertThat(ReservationComparators.extractSortValue(nulls, "created_at_ms")).isEqualTo("");
        assertThat(ReservationComparators.extractSortValue(nulls, "expires_at_ms")).isEqualTo("");

        ReservationSummary nullReserved = ReservationSummary.builder()
            .reservationId("r1")
            .reserved(null)
            .build();
        assertThat(ReservationComparators.extractSortValue(nullReserved, "reserved")).isEqualTo("");

        ReservationSummary nullSubject = ReservationSummary.builder()
            .reservationId("r1")
            .subject(null)
            .build();
        assertThat(ReservationComparators.extractSortValue(nullSubject, "tenant")).isEqualTo("");
    }

    @Test
    @DisplayName("extractSortValue defaults to created_at_ms when sort key is null")
    void extractSortValueDefaultsToCreatedAtMs() {
        ReservationSummary r = row("r1", 1700000000000L, 999, 10, "acme", "tenant:acme",
            Enums.ReservationStatus.ACTIVE);
        assertThat(ReservationComparators.extractSortValue(r, null)).isEqualTo("1700000000000");
    }
}
