package com.nequi.ticketing.domain.model;

/** Per-status inventory counters of an event. */
public record Availability(
        String eventId,
        int capacity,
        int available,
        int reserved,
        int pendingConfirmation,
        int sold,
        int complimentary) {

    public Availability {
        if (capacity < 0 || available < 0 || reserved < 0 || pendingConfirmation < 0 || sold < 0 || complimentary < 0) {
            throw new IllegalArgumentException("Inventory counters cannot be negative");
        }
    }

    public static Availability initial(String eventId, int capacity) {
        return new Availability(eventId, capacity, capacity, 0, 0, 0, 0);
    }

    /** Tickets temporarily held (reserved or waiting for confirmation); they are not sales. */
    public int held() {
        return reserved + pendingConfirmation;
    }

    /** Everything that cannot be bought right now: sold, held and complimentary. */
    public int unavailable() {
        return capacity - available;
    }
}
