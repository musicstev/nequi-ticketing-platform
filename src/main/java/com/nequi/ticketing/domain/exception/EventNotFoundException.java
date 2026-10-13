package com.nequi.ticketing.domain.exception;

public final class EventNotFoundException extends DomainException {

    public EventNotFoundException(String eventId) {
        super("Event not found: " + eventId);
    }
}
