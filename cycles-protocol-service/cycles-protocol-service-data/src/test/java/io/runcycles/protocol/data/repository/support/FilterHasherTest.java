package io.runcycles.protocol.data.repository.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FilterHasher")
class FilterHasherTest {

    @Test
    @DisplayName("same inputs produce same hash")
    void deterministic() {
        String h1 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null, null, null);
        String h2 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null, null, null);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("different filter values produce different hashes")
    void distinguishes() {
        String h1 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null, null, null);
        String h2 = FilterHasher.hash("acme", null, "COMMITTED", "prod", null, null, null, null, null, null);
        String h3 = FilterHasher.hash("acme", null, "ACTIVE", "dev", null, null, null, null, null, null);
        String h4 = FilterHasher.hash("other", null, "ACTIVE", "prod", null, null, null, null, null, null);
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
        assertThat(h1).isNotEqualTo(h4);
    }

    @Test
    @DisplayName("null and empty string treated equivalently (trailing empties collapse)")
    void nullEmptyEquivalence() {
        // FilterHasher treats null as "" — both represent "no filter applied".
        String hNull = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null);
        String hEmpty = FilterHasher.hash("acme", "", "", "", "", "", "", "", null, null);
        assertThat(hNull).isEqualTo(hEmpty);
    }

    @Test
    @DisplayName("hash is 16 hex chars")
    void shape() {
        String h = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null);
        assertThat(h).hasSize(16);
        assertThat(h).matches("[0-9a-f]{16}");
    }

    // cycles-protocol revision 2026-05-21 — from/to window filter inclusion in hash.
    @Test
    @DisplayName("different from/to values produce different hashes")
    void timeWindowDistinguishes() {
        String base = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null);
        String withFrom = FilterHasher.hash("acme", null, null, null, null, null, null, null, 1700000000000L, null);
        String withTo = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, 1700060000000L);
        String withBoth = FilterHasher.hash("acme", null, null, null, null, null, null, null, 1700000000000L, 1700060000000L);
        assertThat(base).isNotEqualTo(withFrom);
        assertThat(base).isNotEqualTo(withTo);
        assertThat(withFrom).isNotEqualTo(withTo);
        assertThat(withFrom).isNotEqualTo(withBoth);
    }

    @Test
    @DisplayName("from/to are positional — swapping yields a different hash")
    void timeWindowPositional() {
        // Defensive: ensure a future refactor that flips fromMs/toMs in the canonical form
        // is caught. The two values are positionally distinct in the canonical string.
        String forward = FilterHasher.hash("acme", null, null, null, null, null, null, null, 100L, 200L);
        String swapped = FilterHasher.hash("acme", null, null, null, null, null, null, null, 200L, 100L);
        assertThat(forward).isNotEqualTo(swapped);
    }

    @Test
    @DisplayName("v0.1.25.18 back-compat: null from/to produces the pre-window 8-field hash byte-exactly")
    void preservesPreWindowHashWhenBothBoundsNull() {
        // Locks down the wire back-compat contract: a sorted-path cursor issued by
        // v0.1.25.12–v0.1.25.18 (which had no from/to fields in the canonical form)
        // must continue to validate against v0.1.25.20 when the client never sends
        // the new params. The golden value is the truncated (8-byte / 16-hex) SHA-256
        // of "t=acme|i=|st=|ws=|ap=|wf=|ag=|ts=" — the pre-window canonical form.
        // Verified independently via:
        //   python -c "import hashlib; print(hashlib.sha256(b't=acme|i=|st=|ws=|ap=|wf=|ag=|ts=').hexdigest()[:16])"
        // → 2f397ea0e8fb53b7
        String hash = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null);
        assertThat(hash).isEqualTo("2f397ea0e8fb53b7");
    }
}
