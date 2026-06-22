package io.runcycles.protocol.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReservationInclude.parseCsv")
class ReservationIncludeTest {

    @Test
    @DisplayName("null and blank yield an empty set")
    void nullAndBlank() {
        assertThat(ReservationInclude.parseCsv(null)).isEmpty();
        assertThat(ReservationInclude.parseCsv("")).isEmpty();
        assertThat(ReservationInclude.parseCsv("   ")).isEmpty();
    }

    @Test
    @DisplayName("recognized single tokens parse")
    void singleTokens() {
        assertThat(ReservationInclude.parseCsv("metadata"))
                .containsExactly(ReservationInclude.METADATA);
        assertThat(ReservationInclude.parseCsv("committed_metadata"))
                .containsExactly(ReservationInclude.COMMITTED_METADATA);
        assertThat(ReservationInclude.parseCsv("evidence"))
                .containsExactly(ReservationInclude.EVIDENCE);
    }

    @Test
    @DisplayName("all three projection tokens parse together")
    void allTokens() {
        assertThat(ReservationInclude.parseCsv("metadata,committed_metadata,evidence"))
                .containsExactlyInAnyOrder(
                        ReservationInclude.METADATA,
                        ReservationInclude.COMMITTED_METADATA,
                        ReservationInclude.EVIDENCE);
    }

    @Test
    @DisplayName("comma-separated list parses both, order-independent")
    void bothTokens() {
        Set<ReservationInclude> parsed =
                ReservationInclude.parseCsv("committed_metadata,metadata");
        assertThat(parsed).isEqualTo(
                EnumSet.of(ReservationInclude.METADATA, ReservationInclude.COMMITTED_METADATA));
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed and matching is case-insensitive")
    void whitespaceAndCase() {
        assertThat(ReservationInclude.parseCsv("  METADATA , Committed_Metadata "))
                .containsExactlyInAnyOrder(
                        ReservationInclude.METADATA, ReservationInclude.COMMITTED_METADATA);
    }

    @Test
    @DisplayName("unrecognized and empty tokens are ignored without error")
    void unknownAndEmptyIgnored() {
        // trailing comma + bogus token + a valid one
        assertThat(ReservationInclude.parseCsv("metadata,,bogus,"))
                .containsExactly(ReservationInclude.METADATA);
        assertThat(ReservationInclude.parseCsv("nope")).isEmpty();
        assertThat(ReservationInclude.parseCsv(",")).isEmpty();
    }

    @Test
    @DisplayName("duplicate tokens collapse")
    void duplicatesCollapse() {
        assertThat(ReservationInclude.parseCsv("metadata,metadata"))
                .containsExactly(ReservationInclude.METADATA);
    }

    @Test
    @DisplayName("wire() exposes the token spelling")
    void wireSpelling() {
        assertThat(ReservationInclude.METADATA.wire()).isEqualTo("metadata");
        assertThat(ReservationInclude.COMMITTED_METADATA.wire()).isEqualTo("committed_metadata");
        assertThat(ReservationInclude.EVIDENCE.wire()).isEqualTo("evidence");
    }
}
