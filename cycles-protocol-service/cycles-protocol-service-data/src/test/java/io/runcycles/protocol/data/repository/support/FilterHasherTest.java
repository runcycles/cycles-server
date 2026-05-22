package io.runcycles.protocol.data.repository.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FilterHasher")
class FilterHasherTest {

    @Test
    @DisplayName("same inputs produce same hash")
    void deterministic() {
        String h1 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null, null, null, null, null, null, null);
        String h2 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null, null, null, null, null, null, null);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("different filter values produce different hashes")
    void distinguishes() {
        String h1 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null, null, null, null, null, null, null);
        String h2 = FilterHasher.hash("acme", null, "COMMITTED", "prod", null, null, null, null, null, null, null, null, null, null);
        String h3 = FilterHasher.hash("acme", null, "ACTIVE", "dev", null, null, null, null, null, null, null, null, null, null);
        String h4 = FilterHasher.hash("other", null, "ACTIVE", "prod", null, null, null, null, null, null, null, null, null, null);
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
        assertThat(h1).isNotEqualTo(h4);
    }

    @Test
    @DisplayName("null and empty string treated equivalently (trailing empties collapse)")
    void nullEmptyEquivalence() {
        // FilterHasher treats null as "" — both represent "no filter applied".
        String hNull = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, null, null);
        String hEmpty = FilterHasher.hash("acme", "", "", "", "", "", "", "", null, null, null, null, null, null);
        assertThat(hNull).isEqualTo(hEmpty);
    }

    @Test
    @DisplayName("hash is 16 hex chars")
    void shape() {
        String h = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(h).hasSize(16);
        assertThat(h).matches("[0-9a-f]{16}");
    }

    // cycles-protocol revision 2026-05-21 — from/to window filter inclusion in hash.
    @Test
    @DisplayName("different from/to values produce different hashes")
    void timeWindowDistinguishes() {
        String base = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, null, null);
        String withFrom = FilterHasher.hash("acme", null, null, null, null, null, null, null, 1700000000000L, null, null, null, null, null);
        String withTo = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, 1700060000000L, null, null, null, null);
        String withBoth = FilterHasher.hash("acme", null, null, null, null, null, null, null, 1700000000000L, 1700060000000L, null, null, null, null);
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
        String forward = FilterHasher.hash("acme", null, null, null, null, null, null, null, 100L, 200L, null, null, null, null);
        String swapped = FilterHasher.hash("acme", null, null, null, null, null, null, null, 200L, 100L, null, null, null, null);
        assertThat(forward).isNotEqualTo(swapped);
    }

    @Test
    @DisplayName("v0.1.25.18 back-compat: null from/to produces the pre-window 8-field hash byte-exactly")
    void preservesPreWindowHashWhenBothBoundsNull() {
        // Locks down the wire back-compat contract: a sorted-path cursor issued by
        // v0.1.25.12–v0.1.25.18 (which had no from/to fields in the canonical form)
        // must continue to validate against v0.1.25.20+ when the client never sends
        // the new params. The golden value is the truncated (8-byte / 16-hex) SHA-256
        // of "t=acme|i=|st=|ws=|ap=|wf=|ag=|ts=" — the pre-window canonical form.
        // Verified independently via:
        //   python -c "import hashlib; print(hashlib.sha256(b't=acme|i=|st=|ws=|ap=|wf=|ag=|ts=').hexdigest()[:16])"
        // → 2f397ea0e8fb53b7
        String hash = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(hash).isEqualTo("2f397ea0e8fb53b7");
    }

    // cycles-protocol revision 2026-05-22 — expires_* / finalized_* window-filter
    // inclusion in hash. Independent gating: each pair only emits its canonical
    // block when at least one of its bounds is set.
    @Test
    @DisplayName("expires_* values produce different hashes from base and from from/to")
    void expiresWindowDistinguishes() {
        String base = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, null, null);
        String withExpiresFrom = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, 1700000000000L, null, null, null);
        String withExpiresTo = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, 1700060000000L, null, null);
        String withFromAndExpires = FilterHasher.hash("acme", null, null, null, null, null, null, null, 1700000000000L, 1700060000000L, 1700000000000L, 1700060000000L, null, null);
        assertThat(base).isNotEqualTo(withExpiresFrom);
        assertThat(base).isNotEqualTo(withExpiresTo);
        assertThat(withExpiresFrom).isNotEqualTo(withExpiresTo);
        assertThat(base).isNotEqualTo(withFromAndExpires);
    }

    @Test
    @DisplayName("finalized_* values produce different hashes from base and from from/to and from expires_*")
    void finalizedWindowDistinguishes() {
        String base = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, null, null);
        String withFinalizedFrom = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, 1700000000000L, null);
        String withFinalizedTo = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, null, null, null, 1700060000000L);
        // Distinct from a from/to with same numeric values — gates emit different canonical fragments.
        String withFromTo = FilterHasher.hash("acme", null, null, null, null, null, null, null, 1700000000000L, 1700060000000L, null, null, null, null);
        // Distinct from an expires_* with same numeric values — different prefix in canonical form.
        String withExpires = FilterHasher.hash("acme", null, null, null, null, null, null, null, null, null, 1700000000000L, 1700060000000L, null, null);
        assertThat(base).isNotEqualTo(withFinalizedFrom);
        assertThat(base).isNotEqualTo(withFinalizedTo);
        assertThat(withFinalizedFrom).isNotEqualTo(withFinalizedTo);
        assertThat(withFinalizedFrom).isNotEqualTo(withFromTo);
        assertThat(withFinalizedFrom).isNotEqualTo(withExpires);
    }

    @Test
    @DisplayName("v0.1.25.20 back-compat: from/to set but no expires_*/finalized_* matches v0.1.25.20 canonical form")
    void preservesV01_25_20HashWhenOnlyFromTo() {
        // Independent gating means a sorted-path cursor issued by v0.1.25.20
        // (which had `|fr=|to=` in the canonical form but no expires_*/finalized_*
        // fields) must continue to validate against v0.1.25.21+ when the client
        // never sends the new pairs. The golden value is the truncated (8-byte /
        // 16-hex) SHA-256 of
        //   "t=acme|i=|st=|ws=|ap=|wf=|ag=|ts=|fr=100|to=200"
        // Verified independently via:
        //   python -c "import hashlib; print(hashlib.sha256(b't=acme|i=|st=|ws=|ap=|wf=|ag=|ts=|fr=100|to=200').hexdigest()[:16])"
        String hash = FilterHasher.hash("acme", null, null, null, null, null, null, null, 100L, 200L, null, null, null, null);
        // → ad7204d521cfd133. Locking this byte-exactly ensures a future refactor
        // that accidentally drops the gating (and adds |ef=|et=|ff=|ft= even when
        // all four are null) breaks this assertion.
        assertThat(hash).isEqualTo("ad7204d521cfd133");
    }
}
