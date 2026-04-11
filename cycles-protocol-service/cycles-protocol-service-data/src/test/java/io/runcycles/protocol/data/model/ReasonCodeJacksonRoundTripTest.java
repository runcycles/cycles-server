package io.runcycles.protocol.data.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.model.DecisionResponse;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ReservationCreateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson round-trip test for {@link Enums.ReasonCode} wire serialization.
 *
 * <p>Guards the spec-compliance claim documented in AUDIT.md under the v0.1.25.7 entry
 * and reinforced in runcycles/cycles-protocol#29: the typed Java enum is a tighter
 * subset of the spec's open-string {@code DecisionReasonCode} wire type and
 * serializes byte-identically to a raw string for every documented known value.
 *
 * <p>Each test serializes a response containing a {@link Enums.ReasonCode} constant,
 * asserts the JSON carries the exact {@code name()} as a plain string, deserializes
 * it back, and asserts the round-trip value equals the original. Any accidental
 * change to enum naming (adding a non-{@code name()} {@code @JsonValue}, adding a
 * custom deserializer that lowercases, etc.) would fail these tests at build time
 * before it could ship and silently break clients.
 *
 * <p>Covers both response types that carry {@code reason_code}:
 * {@link DecisionResponse} (emitted by /v1/decide) and
 * {@link ReservationCreateResponse} (emitted by /v1/reservations, including
 * dry_run idempotency replay).
 *
 * <p>The ObjectMapper is instantiated with defaults to match the behavior of the
 * server's shared mapper at {@link io.runcycles.protocol.data.config.RedisConfig}
 * for the reason_code field specifically — neither side has custom ReasonCode
 * binding, so default serialization applies.
 */
@DisplayName("Enums.ReasonCode Jackson round-trip")
class ReasonCodeJacksonRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * All 6 v0.1.25 base known values. Kept as an explicit list so a future enum
     * addition (e.g., v0.1.26 extension codes) is caught by the parameterized tests
     * below automatically via {@code @EnumSource}, while this list documents the
     * baseline that shipped in v0.1.25.7.
     */
    private static final List<Enums.ReasonCode> V_0_1_25_BASE_VALUES = List.of(
            Enums.ReasonCode.BUDGET_EXCEEDED,
            Enums.ReasonCode.BUDGET_FROZEN,
            Enums.ReasonCode.BUDGET_CLOSED,
            Enums.ReasonCode.BUDGET_NOT_FOUND,
            Enums.ReasonCode.OVERDRAFT_LIMIT_EXCEEDED,
            Enums.ReasonCode.DEBT_OUTSTANDING
    );

    @Test
    void shouldHaveExactlySixV01025BaseValues() {
        // If this fails, a new ReasonCode was added. That's fine — just update the
        // baseline list above AND verify the corresponding cycles-protocol-v0.yaml
        // KNOWN VALUES list (in DecisionReasonCode schema description) lists the
        // new value. The server's typed enum MUST stay a subset of the spec's
        // documented known values; the spec MAY list additional values the server
        // hasn't adopted yet (e.g., v0.1.26 extension codes).
        assertThat(Enums.ReasonCode.values())
                .hasSize(6)
                .containsExactlyElementsOf(V_0_1_25_BASE_VALUES);
    }

    @ParameterizedTest
    @EnumSource(Enums.ReasonCode.class)
    @DisplayName("DecisionResponse.reason_code — serialize produces enum name as JSON string")
    void shouldSerializeDecisionResponseReasonCodeAsEnumName(Enums.ReasonCode value) throws Exception {
        DecisionResponse response = DecisionResponse.builder()
                .decision(Enums.DecisionEnum.DENY)
                .reasonCode(value)
                .affectedScopes(List.of("tenant:acme"))
                .build();

        String json = objectMapper.writeValueAsString(response);

        // Wire format: "reason_code":"<NAME>" with the exact enum constant name, no
        // quoting tricks, no transformation.
        assertThat(json).contains("\"reason_code\":\"" + value.name() + "\"");
    }

    @ParameterizedTest
    @EnumSource(Enums.ReasonCode.class)
    @DisplayName("DecisionResponse.reason_code — deserialize accepts enum name as JSON string")
    void shouldDeserializeDecisionResponseReasonCodeFromEnumName(Enums.ReasonCode value) throws Exception {
        String json = "{\"decision\":\"DENY\",\"reason_code\":\"" + value.name()
                + "\",\"affected_scopes\":[\"tenant:acme\"]}";

        DecisionResponse response = objectMapper.readValue(json, DecisionResponse.class);

        assertThat(response.getReasonCode()).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(Enums.ReasonCode.class)
    @DisplayName("DecisionResponse.reason_code — round-trip preserves the enum value")
    void shouldRoundTripDecisionResponseReasonCode(Enums.ReasonCode value) throws Exception {
        DecisionResponse original = DecisionResponse.builder()
                .decision(Enums.DecisionEnum.DENY)
                .reasonCode(value)
                .affectedScopes(List.of("tenant:acme"))
                .build();

        String json = objectMapper.writeValueAsString(original);
        DecisionResponse deserialized = objectMapper.readValue(json, DecisionResponse.class);
        String rejson = objectMapper.writeValueAsString(deserialized);

        // The critical guarantee for idempotency replay at
        // RedisReservationRepository.java:695 — when the server reads a cached
        // response from Redis and writes it back to the client, the reasonCode
        // value must survive untouched.
        assertThat(deserialized.getReasonCode()).isEqualTo(value);
        assertThat(rejson).isEqualTo(json);
    }

    @ParameterizedTest
    @EnumSource(Enums.ReasonCode.class)
    @DisplayName("ReservationCreateResponse.reason_code — serialize produces enum name as JSON string")
    void shouldSerializeReservationCreateResponseReasonCodeAsEnumName(Enums.ReasonCode value) throws Exception {
        ReservationCreateResponse response = ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.DENY)
                .reasonCode(value)
                .affectedScopes(List.of("tenant:acme"))
                .scopePath("tenant:acme")
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"reason_code\":\"" + value.name() + "\"");
    }

    @ParameterizedTest
    @EnumSource(Enums.ReasonCode.class)
    @DisplayName("ReservationCreateResponse.reason_code — deserialize accepts enum name as JSON string")
    void shouldDeserializeReservationCreateResponseReasonCodeFromEnumName(Enums.ReasonCode value) throws Exception {
        String json = "{\"decision\":\"DENY\",\"reason_code\":\"" + value.name()
                + "\",\"affected_scopes\":[\"tenant:acme\"],\"scope_path\":\"tenant:acme\"}";

        ReservationCreateResponse response = objectMapper.readValue(json, ReservationCreateResponse.class);

        assertThat(response.getReasonCode()).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(Enums.ReasonCode.class)
    @DisplayName("ReservationCreateResponse.reason_code — round-trip preserves the enum value")
    void shouldRoundTripReservationCreateResponseReasonCode(Enums.ReasonCode value) throws Exception {
        ReservationCreateResponse original = ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.DENY)
                .reasonCode(value)
                .affectedScopes(List.of("tenant:acme"))
                .scopePath("tenant:acme")
                .build();

        String json = objectMapper.writeValueAsString(original);
        ReservationCreateResponse deserialized = objectMapper.readValue(json, ReservationCreateResponse.class);
        String rejson = objectMapper.writeValueAsString(deserialized);

        // The critical guarantee for dry_run idempotency replay at
        // RedisReservationRepository.java:179.
        assertThat(deserialized.getReasonCode()).isEqualTo(value);
        assertThat(rejson).isEqualTo(json);
    }

    @Test
    @DisplayName("Null reasonCode is omitted from JSON (NON_NULL inclusion)")
    void shouldOmitNullReasonCodeFromDecisionResponseJson() throws Exception {
        DecisionResponse response = DecisionResponse.builder()
                .decision(Enums.DecisionEnum.ALLOW)
                .affectedScopes(List.of("tenant:acme"))
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("reason_code");
    }

    @Test
    @DisplayName("Null reasonCode is omitted from ReservationCreateResponse JSON (NON_NULL inclusion)")
    void shouldOmitNullReasonCodeFromReservationCreateResponseJson() throws Exception {
        ReservationCreateResponse response = ReservationCreateResponse.builder()
                .decision(Enums.DecisionEnum.ALLOW)
                .affectedScopes(List.of("tenant:acme"))
                .scopePath("tenant:acme")
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("reason_code");
    }
}
