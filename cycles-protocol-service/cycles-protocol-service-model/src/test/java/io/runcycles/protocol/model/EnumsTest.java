package io.runcycles.protocol.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip coverage for the v0.1.25.13 Jackson wire-form annotations on
 * ReservationSortBy and SortDirection. The admin plane has the same pattern
 * on its SortSpec / SortDirection primitives; this mirrors the runtime-plane
 * variant to guarantee lowercase-on-wire contract stays intact.
 */
@DisplayName("Enums — wire-form annotations")
class EnumsTest {

    // ---- ReservationSortBy ----

    @Test
    @DisplayName("ReservationSortBy.getWire emits lowercase wire form")
    void reservationSortByGetWireLowercase() {
        assertThat(Enums.ReservationSortBy.CREATED_AT_MS.getWire()).isEqualTo("created_at_ms");
        assertThat(Enums.ReservationSortBy.RESERVATION_ID.getWire()).isEqualTo("reservation_id");
        assertThat(Enums.ReservationSortBy.EXPIRES_AT_MS.getWire()).isEqualTo("expires_at_ms");
        assertThat(Enums.ReservationSortBy.SCOPE_PATH.getWire()).isEqualTo("scope_path");
    }

    @Test
    @DisplayName("ReservationSortBy.fromWire parses canonical lowercase")
    void reservationSortByFromWireLowercase() {
        assertThat(Enums.ReservationSortBy.fromWire("created_at_ms"))
            .isEqualTo(Enums.ReservationSortBy.CREATED_AT_MS);
        assertThat(Enums.ReservationSortBy.fromWire("reservation_id"))
            .isEqualTo(Enums.ReservationSortBy.RESERVATION_ID);
    }

    @Test
    @DisplayName("ReservationSortBy.fromWire is case-insensitive (uppercase + mixed)")
    void reservationSortByFromWireCaseInsensitive() {
        assertThat(Enums.ReservationSortBy.fromWire("CREATED_AT_MS"))
            .isEqualTo(Enums.ReservationSortBy.CREATED_AT_MS);
        assertThat(Enums.ReservationSortBy.fromWire("Created_At_Ms"))
            .isEqualTo(Enums.ReservationSortBy.CREATED_AT_MS);
    }

    @Test
    @DisplayName("ReservationSortBy.fromWire(null) → null (controller upgrades to 400)")
    void reservationSortByFromWireNull() {
        assertThat(Enums.ReservationSortBy.fromWire(null)).isNull();
    }

    @Test
    @DisplayName("ReservationSortBy.fromWire throws on unknown value")
    void reservationSortByFromWireUnknown() {
        assertThatThrownBy(() -> Enums.ReservationSortBy.fromWire("bogus"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ReservationSortBy round-trip getWire → fromWire is identity")
    void reservationSortByRoundTrip() {
        for (Enums.ReservationSortBy value : Enums.ReservationSortBy.values()) {
            assertThat(Enums.ReservationSortBy.fromWire(value.getWire())).isEqualTo(value);
        }
    }

    // ---- SortDirection ----

    @Test
    @DisplayName("SortDirection.getWire emits lowercase wire form")
    void sortDirectionGetWireLowercase() {
        assertThat(Enums.SortDirection.ASC.getWire()).isEqualTo("asc");
        assertThat(Enums.SortDirection.DESC.getWire()).isEqualTo("desc");
    }

    @Test
    @DisplayName("SortDirection.fromWire parses canonical lowercase")
    void sortDirectionFromWireLowercase() {
        assertThat(Enums.SortDirection.fromWire("asc")).isEqualTo(Enums.SortDirection.ASC);
        assertThat(Enums.SortDirection.fromWire("desc")).isEqualTo(Enums.SortDirection.DESC);
    }

    @Test
    @DisplayName("SortDirection.fromWire is case-insensitive (uppercase + mixed)")
    void sortDirectionFromWireCaseInsensitive() {
        assertThat(Enums.SortDirection.fromWire("ASC")).isEqualTo(Enums.SortDirection.ASC);
        assertThat(Enums.SortDirection.fromWire("Desc")).isEqualTo(Enums.SortDirection.DESC);
    }

    @Test
    @DisplayName("SortDirection.fromWire(null) → null (controller default-applies DESC)")
    void sortDirectionFromWireNull() {
        assertThat(Enums.SortDirection.fromWire(null)).isNull();
    }

    @Test
    @DisplayName("SortDirection.fromWire throws on unknown value")
    void sortDirectionFromWireUnknown() {
        assertThatThrownBy(() -> Enums.SortDirection.fromWire("sideways"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SortDirection round-trip getWire → fromWire is identity")
    void sortDirectionRoundTrip() {
        for (Enums.SortDirection value : Enums.SortDirection.values()) {
            assertThat(Enums.SortDirection.fromWire(value.getWire())).isEqualTo(value);
        }
    }
}
