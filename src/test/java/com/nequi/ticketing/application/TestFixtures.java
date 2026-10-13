package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.model.Event;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

final class TestFixtures {

    static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private TestFixtures() {}

    static Event event(String id, int capacity) {
        return new Event(id, "Concert", NOW.plusSeconds(86_400), "Arena", capacity, NOW);
    }
}
