package io.runcycles.protocol.data.repository;

import io.runcycles.protocol.data.service.EvidenceEmitter;
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
    private static final String HEX_C = "c".repeat(64);

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
        when(jedis.hgetAll("reservation:res_res-ev")).thenReturn(fields);

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
        when(jedis.hgetAll("reservation:res_bare")).thenReturn(reservationFields("bare", "ACTIVE"));

        assertThat(repository.getReservationById("bare").getEvidence()).isNull();
    }

    @Test
    @DisplayName("a half-written ref (id without url) is ignored")
    void partialRefIgnored() {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        Map<String, String> fields = reservationFields("res-half", "ACTIVE");
        fields.put("reserve_evidence_id", HEX_A); // url missing
        when(jedis.hgetAll("reservation:res_res-half")).thenReturn(fields);

        assertThat(repository.getReservationById("res-half").getEvidence()).isNull();
    }

    // ---- persistence (persistEvidenceRef writes id + url) ----

    @Test
    @DisplayName("persistEvidenceRef HSETs both id and url onto the reservation hash")
    void persistsRef() throws Exception {
        when(jedisPool.getResource()).thenReturn(jedis);
        doNothing().when(jedis).close();
        EvidenceEmitter.EvidenceRef ref =
                new EvidenceEmitter.EvidenceRef(HEX_C, "http://h/v1/evidence/" + HEX_C);

        invokePersist("res-p", "commit", ref);

        verify(jedis).hset(eq("reservation:res_res-p"), argThat((Map<String, String> m) ->
                HEX_C.equals(m.get("commit_evidence_id"))
                        && ("http://h/v1/evidence/" + HEX_C).equals(m.get("commit_evidence_url"))));
    }

    @Test
    @DisplayName("persistEvidenceRef is a no-op for a null ref or null reservation id")
    void persistNoOp() throws Exception {
        invokePersist("res-p", "reserve", null);
        invokePersist(null, "reserve",
                new EvidenceEmitter.EvidenceRef(HEX_C, "http://h/v1/evidence/" + HEX_C));
        // Never touched the pool / wrote anything.
        verify(jedisPool, never()).getResource();
        verify(jedis, never()).hset(anyString(), anyMap());
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

    private void invokePersist(String id, String artifact, EvidenceEmitter.EvidenceRef ref) throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod(
                "persistEvidenceRef", String.class, String.class, EvidenceEmitter.EvidenceRef.class);
        m.setAccessible(true);
        m.invoke(repository, id, artifact, ref);
    }

    private ReservationSummary invokeToSummary(ReservationDetail detail, Set<ReservationInclude> include)
            throws Exception {
        Method m = RedisReservationRepository.class.getDeclaredMethod(
                "toSummary", ReservationDetail.class, Set.class);
        m.setAccessible(true);
        return (ReservationSummary) m.invoke(repository, detail, include);
    }
}
