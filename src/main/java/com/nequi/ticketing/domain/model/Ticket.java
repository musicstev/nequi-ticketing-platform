package com.nequi.ticketing.domain.model;

import java.time.Instant;

public record Ticket(
        String eventId, String ticketId, TicketStatus status, String orderId, Instant expiresAt, long version) {

    /** A ticket state change together with its audit record. */
    public record Change(Ticket ticket, TicketTransition transition) {}

    public static Ticket newAvailable(String eventId, int sequence) {
        return new Ticket(eventId, "T-%06d".formatted(sequence), TicketStatus.AVAILABLE, null, null, 0);
    }

    /**
     * Applies a validated transition. {@code orderId}/{@code expiresAt} are only meaningful when
     * reserving; later holds keep the order, releasing clears it.
     */
    public Change transitionTo(
            TicketStatus target, String newOrderId, Instant newExpiresAt, TransitionReason reason, Instant at) {
        status.requireTransitionTo(target);
        String nextOrderId = orderId;
        Instant nextExpiry = expiresAt;
        switch (target) {
            case RESERVED -> {
                if (newOrderId == null || newExpiresAt == null) {
                    throw new IllegalArgumentException("A reservation needs an order and an expiry");
                }
                nextOrderId = newOrderId;
                nextExpiry = newExpiresAt;
            }
            case AVAILABLE, COMPLIMENTARY -> {
                nextOrderId = null;
                nextExpiry = null;
            }
            case SOLD -> nextExpiry = null;
            case PENDING_CONFIRMATION -> { /* keeps order and expiry */ }
        }
        Ticket next = new Ticket(eventId, ticketId, target, nextOrderId, nextExpiry, version + 1);
        return new Change(next, new TicketTransition(eventId, ticketId, nextOrderId == null ? orderId : nextOrderId,
                status, target, reason, at));
    }

    public boolean isExpiredAt(Instant now) {
        return status.isHold() && expiresAt != null && !expiresAt.isAfter(now);
    }
}
