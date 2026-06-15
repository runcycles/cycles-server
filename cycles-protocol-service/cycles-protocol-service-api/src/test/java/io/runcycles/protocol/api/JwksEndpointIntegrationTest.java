package io.runcycles.protocol.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live serving test for {@code getEvidenceJwks} over real HTTP — full
 * {@link org.springframework.boot.test.context.SpringBootTest} on a random port
 * with the SECURITY FILTER CHAIN ACTIVE (unlike the {@code @WebMvcTest} unit
 * test, which disables filters). Proves the JWK Set endpoint is reachable
 * WITHOUT an API key (the public-path exemption actually works end-to-end), that
 * the served JWK matches the configured signing identity, and — via the base
 * class's contract-validating interceptor — that the body conforms to the
 * published {@code CyclesEvidenceJwks} schema.
 */
@TestPropertySource(properties = {
        "cycles.evidence.signing.signer-did=" + JwksEndpointIntegrationTest.SIGNER_DID,
        "cycles.evidence.signing.kid=2026-06",
        "cycles.evidence.signing.nbf-ms=1810000000000"
})
class JwksEndpointIntegrationTest extends BaseIntegrationTest {

    static final String SIGNER_DID =
            "ec52b49b81eb29ef6f62947cade245c715bf943b7ef2a5f2789288574466fc43";

    private static final String JWKS_PATH = "/v1/.well-known/cycles-jwks.json";

    @Test
    void servesJwkSetPubliclyWithoutApiKey() throws Exception {
        // No X-Cycles-API-Key header — proves the public-path exemption holds
        // through the real Spring Security filter chain (not just the array).
        ResponseEntity<String> resp =
                restTemplate.getForEntity(baseUrl() + JWKS_PATH, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getCacheControl())
                .contains("max-age").contains("public").doesNotContain("immutable");

        JsonNode jwk = objectMapper.readTree(resp.getBody()).get("keys").get(0);
        assertThat(jwk.get("kty").asText()).isEqualTo("OKP");
        assertThat(jwk.get("crv").asText()).isEqualTo("Ed25519");
        assertThat(jwk.get("kid").asText()).isEqualTo("2026-06");
        assertThat(jwk.get("cycles_nbf_ms").asLong()).isEqualTo(1810000000000L);
        assertThat(jwk.get("status").asText()).isEqualTo("active");
        // the served x decodes to exactly the configured signer_did bytes
        byte[] x = Base64.getUrlDecoder().decode(jwk.get("x").asText());
        assertThat(x).isEqualTo(HexFormat.of().parseHex(SIGNER_DID));
    }

    @Test
    void bogusApiKeyStillServesTheSet() {
        // A public endpoint must serve regardless of credentials — a junk key
        // must not turn a 200 into a 401.
        ResponseEntity<String> resp = restTemplate.exchange(
                baseUrl() + JWKS_PATH,
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headersForTenant("cyc_not_a_real_key")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
