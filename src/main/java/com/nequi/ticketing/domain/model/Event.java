package com.nequi.ticketing.domain.model;

import java.time.Instant;

public record Event(String id, String name, Instant date, String venue, int capacity, Instant createdAt) {

    /** Upper bound keeps per-event inventory (one item per ticket) and its creation cost bounded. */
    public static final int MAX_CAPACITY = 10_000;

    public Event {
        requireText(id, "id");
        requireText(name, "name");
        requireText(venue, "venue");
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CAPACITY);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
