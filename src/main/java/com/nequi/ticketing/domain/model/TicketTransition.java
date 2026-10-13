package com.nequi.ticketing.domain.model;

import java.time.Instant;

/** Immutable audit record of one ticket state change. */
public record TicketTransition(
        String eventId,
        String ticketId,
        String orderId,
        TicketStatus from,
        TicketStatus to,
        TransitionReason reason,
        Instant at) {}
