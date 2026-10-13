package com.nequi.ticketing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventAndAvailabilityTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    @Test
    void validEventIsCreated() {
        Event event = new Event("evt-1", "Concert", NOW.plusSeconds(3600), "Arena", 100, NOW);
        assertThat(event.capacity()).isEqualTo(100);
    }

    @Test
    void invalidEventsAreRejected() {
        Instant date = NOW.plusSeconds(10);
        assertThatThrownBy(() -> new Event(" ", "Concert", date, "Arena", 100, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event("e", " ", date, "Arena", 100, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event("e", "n", date, null, 100, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event("e", "n", null, "v", 100, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event("e", "n", date, "v", 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Event("e", "n", date, "v", Event.MAX_CAPACITY + 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availabilityAccountsHoldsSalesAndComplimentaryAsUnavailable() {
        Availability availability = new Availability("evt-1", 100, 70, 10, 5, 12, 3);
        assertThat(availability.held()).isEqualTo(15);
        assertThat(availability.unavailable()).isEqualTo(30);
    }

    @Test
    void initialAvailabilityHasAllTicketsAvailable() {
        Availability availability = Availability.initial("evt-1", 50);
        assertThat(availability.available()).isEqualTo(50);
        assertThat(availability.sold()).isZero();
    }

    @Test
    void negativeCountersAreRejected() {
        assertThatThrownBy(() -> new Availability("e", 10, -1, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
