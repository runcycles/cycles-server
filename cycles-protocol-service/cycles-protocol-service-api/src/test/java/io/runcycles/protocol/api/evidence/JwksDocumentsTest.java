package io.runcycles.protocol.api.evidence;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private static Map<String, Object> firstKey(Optional<Map<String, Object>> set) {
        assertThat(set).isPresent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) set.get().get("keys");
        return keys.get(0);
    }
}
