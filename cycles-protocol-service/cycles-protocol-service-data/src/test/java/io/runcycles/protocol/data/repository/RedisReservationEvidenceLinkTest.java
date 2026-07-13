package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the reservation -> evidence linkage (spec v0.1.25.9): persisting the
 * computed evidence ref onto the reservation hash, hydrating it back into the
 * {@code evidence} field, and the {@code include=evidence} list projection.
 */
@DisplayName("RedisReservationRepository — evidence linkage")
class RedisReservationEvidenceLinkTest extends BaseRedisReservationRepositoryTest {

    private static final String HEX_A = "a".repeat(64);
    private static final String HEX_B = "b".repeat(64);

    // ---- hydration (getReservationById builds detail.evidence) ----

    @Test
    @DisplayName("getReservationById hydrates reserve + commit evidence refs from the hash")
    void hydratesEvidence() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        Map<String, String> fields = reservationFields("res-ev", "COMMITTED");
        fields.put("charged_amount", "3000");
        fields.put("reserve_evidence_id", HEX_A);
        fields.put("reserve_evidence_url", "http://h/v1/evidence/" + HEX_A);
        fields.put("commit_evidence_id", HEX_B);
        fields.put("commit_evidence_url", "http://h/v1/evidence/" + HEX_B);
        mockReservationHash("reservation:res_res-ev", fields);

        ReservationDetail detail = repository.getReservationById("res-ev");

        assertThat(detail.getEvidence()).isNotNull();
        assertThat(detail.getEvidence().getReserve().getEvidenceId()).isEqualTo(HEX_A);
        assertThat(detail.getEvidence().getReserve().getCyclesEvidenceUrl())
                .isEqualTo("http://h/v1/evidence/" + HEX_A);
        assertThat(detail.getEvidence().getCommit().getEvidenceId()).isEqualTo(HEX_B);
        assertThat(detail.getEvidence().getRelease()).isNull();
    }

    @Test
    @DisplayName("getReservationById leaves evidence null when no refs were recorded")
    void noEvidenceWhenAbsent() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        mockReservationHash("reservation:res_bare", reservationFields("bare", "ACTIVE"));

        assertThat(repository.getReservationById("bare").getEvidence()).isNull();
    }

    @Test
    @DisplayName("a half-written ref (id without url) is ignored")
    void partialRefIgnored() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        Map<String, String> fields = reservationFields("res-half", "ACTIVE");
        fields.put("reserve_evidence_id", HEX_A); // url missing
        mockReservationHash("reservation:res_res-half", fields);

        assertThat(repository.getReservationById("res-half").getEvidence()).isNull();
    }

    // ---- projection (toSummary gates evidence on include=evidence) ----

    @Test
    @DisplayName("toSummary projects evidence only when include=evidence")
    void projectionGated() throws Exception {
        ReservationDetail detail = new ReservationDetail();
        detail.setReservationId("res-x");
        detail.setEvidence(ReservationEvidence.builder()
                .reserve(CyclesEvidenceRef.builder().evidenceId(HEX_A)
                        .cyclesEvidenceUrl("http://h/v1/evidence/" + HEX_A).build())
                .build());

        assertThat(invokeToSummary(detail, EnumSet.of(ReservationInclude.EVIDENCE)).getEvidence())
                .isNotNull();
        assertThat(invokeToSummary(detail, EnumSet.noneOf(ReservationInclude.class)).getEvidence())
                .isNull();
    }

    // ---- reflection helpers ----

    private ReservationSummary invokeToSummary(ReservationDetail detail, Set<ReservationInclude> include)
            throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod(
                "toSummary", ReservationDetail.class, Set.class);
        m.setAccessible(true);
        return (ReservationSummary) m.invoke(repository, detail, include);
    }
}
