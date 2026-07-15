package io.runcycles.protocol.api.evidence;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class JwksDocumentsTest {

    // A valid raw 64-hex Ed25519 public key (lowercase).
    private static final String RAW_HEX =
            "207a067892821e25d770f1fba0c47c11ff4b813e54162ece9eb839e076231ab6";

    @Test
    void rawHexKey_buildsOneActiveEd25519Jwk() {
        Optional<Map<String, Object>> set = JwksDocuments.jwkSet(RAW_HEX, "", 0L);

        assertThat(set).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) set.get().get("keys");
        assertThat(keys).hasSize(1);

        Map<String, Object> jwk = keys.get(0);
        assertThat(jwk).containsEntry("kty", "OKP")
                .containsEntry("crv", "Ed25519")
                .containsEntry("alg", "EdDSA")
                .containsEntry("cycles_nbf_ms", 0L)
                .containsEntry("status", "active");
        // active ⇒ cycles_exp_ms omitted entirely.
        assertThat(jwk).doesNotContainKey("cycles_exp_ms");
    }

    @Test
    void jwkX_isBase64UrlOfTheRawSignerDidBytes() {
        Map<String, Object> jwk = firstKey(JwksDocuments.jwkSet(RAW_HEX, "", 0L));

        String x = (String) jwk.get("x");
        byte[] decoded = Base64.getUrlDecoder().decode(x);
        // x decodes back to exactly the 32 raw bytes hex-decoded from signer_did.
        assertThat(decoded).isEqualTo(HexFormat.of().parseHex(RAW_HEX));
        assertThat(x).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    void defaultKid_isFirst16HexCharsLowercased() {
        Map<String, Object> jwk = firstKey(JwksDocuments.jwkSet(RAW_HEX.toUpperCase(), "  ", 0L));
        assertThat(jwk).containsEntry("kid", RAW_HEX.substring(0, 16));
    }

    @Test
    void configuredKid_overridesTheDefault() {
        Map<String, Object> jwk = firstKey(JwksDocuments.jwkSet(RAW_HEX, "2026-06", 0L));
        assertThat(jwk).containsEntry("kid", "2026-06");
    }

    @Test
    void nbfMs_isCarriedThrough() {
        Map<String, Object> jwk = firstKey(JwksDocuments.jwkSet(RAW_HEX, "k", 1810000000000L));
        assertThat(jwk).containsEntry("cycles_nbf_ms", 1810000000000L);
    }

    @Test
    void blankOrNullSignerDid_isEmpty() {
        assertThat(JwksDocuments.jwkSet("", "", 0L)).isEmpty();
        assertThat(JwksDocuments.jwkSet("   ", "", 0L)).isEmpty();
        assertThat(JwksDocuments.jwkSet(null, "", 0L)).isEmpty();
    }

    @Test
    void didCyclesForm_isEmpty_becauseItCarriesNoKeyBytes() {
        String didCycles = "did:cycles:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08#2026-06";
        assertThat(JwksDocuments.jwkSet(didCycles, "", 0L)).isEmpty();
        assertThat(JwksDocuments.isRawHexKey(didCycles)).isFalse();
    }

    @Test
    void malformedHex_isEmpty() {
        assertThat(JwksDocuments.jwkSet("xyz", "", 0L)).isEmpty();         // non-hex
        assertThat(JwksDocuments.jwkSet(RAW_HEX.substring(1), "", 0L)).isEmpty(); // 63 chars
        assertThat(JwksDocuments.jwkSet(RAW_HEX + "ab", "", 0L)).isEmpty();       // 66 chars
    }

    @Test
    void isRawHexKey_acceptsTrimmedAndMixedCase() {
        assertThat(JwksDocuments.isRawHexKey("  " + RAW_HEX.toUpperCase() + "  ")).isTrue();
    }

    // ── Retired-key rotation history ──
    private static final String RETIRED_HEX =
            "ec52b49b81eb29ef6f62947cade245c715bf943b7ef2a5f2789288574466fc43";

    @Test
    void retiredKeys_appendedWithBoundedWindowAndRetiredStatus() {
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "2026-06", 1700000000000L,
                List.of(new JwksDocuments.RetiredKey(RETIRED_HEX, "2025-h2", 0L, 1700000000000L))));
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).containsEntry("kid", "2026-06").containsEntry("status", "active")
                .doesNotContainKey("cycles_exp_ms");
        assertThat(keys.get(1)).containsEntry("kid", "2025-h2").containsEntry("status", "retired")
                .containsEntry("cycles_nbf_ms", 0L).containsEntry("cycles_exp_ms", 1700000000000L);
    }

    @Test
    void retiredKey_withNullExp_isSkipped() {
        // A retired key needs a closed window; a null exp is a config error.
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "k", 0L,
                List.of(new JwksDocuments.RetiredKey(RETIRED_HEX, "no-exp", 0L, null))));
        assertThat(keys).hasSize(1); // active only
    }

    @Test
    void retiredKey_malformedHex_isSkipped() {
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "k", 0L,
                List.of(new JwksDocuments.RetiredKey("not-hex", "bad", 0L, 100L))));
        assertThat(keys).hasSize(1);
    }

    @Test
    void retiredKey_duplicateKid_isSkipped() {
        // kid MUST be unique set-wide; a retired key colliding with the active
        // key's kid is dropped (never emit a duplicate kid).
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "dup", 0L,
                List.of(new JwksDocuments.RetiredKey(RETIRED_HEX, "dup", 0L, 100L))));
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).containsEntry("status", "active");
    }

    @Test
    void retiredKey_emptyOrInvertedWindow_isSkipped() {
        // cycles_exp_ms is EXCLUSIVE, so exp == nbf is an empty window and
        // exp < nbf is inverted; neither is publishable.
        assertThat(allKeys(JwksDocuments.jwkSet(RAW_HEX, "k", 0L,
                List.of(new JwksDocuments.RetiredKey(RETIRED_HEX, "empty", 100L, 100L))))).hasSize(1);
        assertThat(allKeys(JwksDocuments.jwkSet(RAW_HEX, "k", 0L,
                List.of(new JwksDocuments.RetiredKey(RETIRED_HEX, "inverted", 200L, 100L))))).hasSize(1);
    }

    @Test
    void retiredKey_sameMaterialAsActiveKey_publishesAfterNbfClamp() {
        // Active nbf 0 with a same-material retired [0,100): clamping the active
        // window to 100 makes the retired window disjoint, so the reused key's
        // history is preserved (both publish) rather than dropped (case-insensitive).
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "active", 0L,
                List.of(new JwksDocuments.RetiredKey(RAW_HEX.toUpperCase(), "dup-material", 0L, 100L))));
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).containsEntry("status", "active").containsEntry("cycles_nbf_ms", 100L);
        assertThat(keys.get(1)).containsEntry("kid", "dup-material").containsEntry("status", "retired");
    }

    @Test
    void retiredKey_sameMaterialAsActiveKey_disjointWindow_isPublished() {
        // Same key reused before it became active again: active window [200, inf),
        // retired [0, 100) — disjoint, so the retired window is preserved and old
        // evidence still resolves.
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "active", 200L,
                List.of(new JwksDocuments.RetiredKey(RAW_HEX.toUpperCase(), "prior", 0L, 100L))));
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).containsEntry("kid", "prior").containsEntry("status", "retired");
    }

    @Test
    void retiredKey_sameMaterialOverlappingWindows_secondIsSkipped() {
        // Same key bytes, distinct kids, OVERLAPPING windows — raw-hex selection
        // (key bytes + issued_at_ms) would be ambiguous, so only the first emits.
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "active", 0L, List.of(
                        new JwksDocuments.RetiredKey(RETIRED_HEX, "win-a", 0L, 200L),
                        new JwksDocuments.RetiredKey(RETIRED_HEX, "win-b", 100L, 300L))));
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).containsEntry("kid", "win-a");
    }

    @Test
    void retiredKey_sameMaterialDisjointWindows_bothEmitted() {
        // Same key reused across NON-overlapping periods is legitimate and
        // unambiguous (exp is exclusive, so [0,100) and [100,200) do not overlap).
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "active", 0L, List.of(
                        new JwksDocuments.RetiredKey(RETIRED_HEX, "early", 0L, 100L),
                        new JwksDocuments.RetiredKey(RETIRED_HEX, "later", 100L, 200L))));
        assertThat(keys).hasSize(3);
    }

    @Test
    void activeNbf_belowLatestRetiredExp_isAdvancedToThatBoundary() {
        // Configured active nbf 0 with a retired window ending at 100 would leave the
        // active key valid since epoch; the published active window is clamped to 100.
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "active", 0L,
                List.of(new JwksDocuments.RetiredKey(RETIRED_HEX, "old", 0L, 100L))));
        assertThat(keys.get(0)).containsEntry("status", "active").containsEntry("cycles_nbf_ms", 100L);
        assertThat(keys.get(1)).containsEntry("kid", "old");
    }

    @Test
    void activeNbf_clampedEvenWhenRetiredKeyMaterialIsMalformed() {
        // A retired entry with bad hex isn't published, but its declared exp still
        // floors the active key — a typo in rotation history must not reopen the
        // pre-rotation backdating hole.
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "active", 0L,
                List.of(new JwksDocuments.RetiredKey("not-hex", "bad", 0L, 100L))));
        assertThat(keys).hasSize(1); // malformed key not published
        assertThat(keys.get(0)).containsEntry("status", "active").containsEntry("cycles_nbf_ms", 100L);
    }

    @Test
    void activeNbf_atOrAboveLatestRetiredExp_isUnchanged() {
        // Correctly-configured rotation (active nbf = rotation time = retired exp) is
        // not modified.
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
                RAW_HEX, "active", 100L,
                List.of(new JwksDocuments.RetiredKey(RETIRED_HEX, "old", 0L, 100L))));
        assertThat(keys.get(0)).containsEntry("cycles_nbf_ms", 100L);
    }

    @Test
    void emptyRetiredList_isSingleActiveKey() {
        assertThat(allKeys(JwksDocuments.jwkSet(RAW_HEX, "k", 0L, List.of()))).hasSize(1);
        assertThat(allKeys(JwksDocuments.jwkSet(RAW_HEX, "k", 0L, null))).hasSize(1);
    }

    @Test
    void nullKidsEntriesAndReverseDisjointWindowsCoverDefensiveBranches() {
        List<Map<String, Object>> keys = allKeys(JwksDocuments.jwkSet(
            RAW_HEX, null, 0L, Arrays.asList(
                null,
                new JwksDocuments.RetiredKey(RETIRED_HEX, null, 100L, 200L),
                new JwksDocuments.RetiredKey(RETIRED_HEX, "earlier", 0L, 100L))));

        assertThat(keys).hasSize(3);
        assertThat(keys.get(0).get("kid")).isEqualTo(RAW_HEX.substring(0, 16));
        assertThat(keys.get(1).get("kid")).isEqualTo(RETIRED_HEX.substring(0, 16));
        assertThat(keys.get(2).get("kid")).isEqualTo("earlier");
    }

    private static Map<String, Object> firstKey(Optional<Map<String, Object>> set) {
        return allKeys(set).get(0);
    }

    private static List<Map<String, Object>> allKeys(Optional<Map<String, Object>> set) {
        assertThat(set).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) set.get().get("keys");
        return keys;
    }
}
