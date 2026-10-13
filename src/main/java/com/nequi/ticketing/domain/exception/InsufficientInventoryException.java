package com.nequi.ticketing.domain.exception;

public final class InsufficientInventoryException extends DomainException {

    public InsufficientInventoryException(String eventId, int requested) {
        super("Not enough available tickets for event " + eventId + " (requested " + requested + ")");
    }
}
