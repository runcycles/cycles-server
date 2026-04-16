package io.runcycles.protocol.data.repository.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FilterHasher")
class FilterHasherTest {

    @Test
    @DisplayName("same inputs produce same hash")
    void deterministic() {
        String h1 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null);
        String h2 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("different filter values produce different hashes")
    void distinguishes() {
        String h1 = FilterHasher.hash("acme", null, "ACTIVE", "prod", null, null, null, null);
        String h2 = FilterHasher.hash("acme", null, "COMMITTED", "prod", null, null, null, null);
        String h3 = FilterHasher.hash("acme", null, "ACTIVE", "dev", null, null, null, null);
        String h4 = FilterHasher.hash("other", null, "ACTIVE", "prod", null, null, null, null);
        assertThat(h1).isNotEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
        assertThat(h1).isNotEqualTo(h4);
    }

    @Test
    @DisplayName("null and empty string treated equivalently (trailing empties collapse)")
    void nullEmptyEquivalence() {
        // FilterHasher treats null as "" — both represent "no filter applied".
        String hNull = FilterHasher.hash("acme", null, null, null, null, null, null, null);
        String hEmpty = FilterHasher.hash("acme", "", "", "", "", "", "", "");
        assertThat(hNull).isEqualTo(hEmpty);
    }

    @Test
    @DisplayName("hash is 16 hex chars")
    void shape() {
        String h = FilterHasher.hash("acme", null, null, null, null, null, null, null);
        assertThat(h).hasSize(16);
        assertThat(h).matches("[0-9a-f]{16}");
    }
}
