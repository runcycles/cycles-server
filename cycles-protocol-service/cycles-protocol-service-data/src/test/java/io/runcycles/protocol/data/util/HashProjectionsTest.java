package io.runcycles.protocol.data.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HashProjections")
class HashProjectionsTest {

    private static final List<String> NAMES = List.of("tenant", "state", "estimate_amount");

    @Test
    void zipsNamesToValuesInCallOrder() {
        assertThat(HashProjections.mapHashFields(NAMES, List.of("acme", "ACTIVE", "5000")))
            .containsOnly(
                java.util.Map.entry("tenant", "acme"),
                java.util.Map.entry("state", "ACTIVE"),
                java.util.Map.entry("estimate_amount", "5000"));
    }

    @Test
    @DisplayName("null HMGET values (absent hash fields) are dropped, not stored as nulls")
    void dropsNullValues() {
        assertThat(HashProjections.mapHashFields(NAMES, Arrays.asList("acme", null, null)))
            .containsOnly(java.util.Map.entry("tenant", "acme"));
    }

    @Test
    @DisplayName("missing key (all-null reply) yields an empty map — callers treat as not-found")
    void allNullReplyYieldsEmptyMap() {
        assertThat(HashProjections.mapHashFields(NAMES, Arrays.asList(null, null, null))).isEmpty();
    }

    @Test
    void nullReplyYieldsEmptyMap() {
        assertThat(HashProjections.mapHashFields(NAMES, null)).isEmpty();
    }

    @Test
    @DisplayName("length mismatch is bounded by the shorter list (defensive against driver quirks)")
    void lengthMismatchIsBounded() {
        assertThat(HashProjections.mapHashFields(NAMES, List.of("acme")))
            .containsOnly(java.util.Map.entry("tenant", "acme"));
        assertThat(HashProjections.mapHashFields(List.of("tenant"), List.of("acme", "extra")))
            .containsOnly(java.util.Map.entry("tenant", "acme"));
    }
}
