package io.runcycles.protocol.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReservationEvidence")
class ReservationEvidenceTest {

    private static CyclesEvidenceRef ref(String hex) {
        return CyclesEvidenceRef.builder()
                .evidenceId(hex).cyclesEvidenceUrl("http://h/v1/evidence/" + hex).build();
    }

    @Test
    @DisplayName("isEmpty is true only when no artifact ref is set")
    void isEmpty() {
        assertThat(ReservationEvidence.builder().build().isEmpty()).isTrue();
        assertThat(ReservationEvidence.builder().reserve(ref("a".repeat(64))).build().isEmpty())
                .isFalse();
        assertThat(ReservationEvidence.builder().commit(ref("b".repeat(64))).build().isEmpty())
                .isFalse();
        assertThat(ReservationEvidence.builder().release(ref("c".repeat(64))).build().isEmpty())
                .isFalse();
    }

    @Test
    @DisplayName("holds a ref per artifact type")
    void holdsRefs() {
        ReservationEvidence e = ReservationEvidence.builder()
                .reserve(ref("a".repeat(64)))
                .commit(ref("b".repeat(64)))
                .build();
        assertThat(e.getReserve().getEvidenceId()).isEqualTo("a".repeat(64));
        assertThat(e.getCommit().getEvidenceId()).isEqualTo("b".repeat(64));
        assertThat(e.getRelease()).isNull();
    }
}
