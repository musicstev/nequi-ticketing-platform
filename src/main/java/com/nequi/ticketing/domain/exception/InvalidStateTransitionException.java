package com.nequi.ticketing.domain.exception;

public final class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String entity, Object from, Object to) {
        super("Illegal " + entity + " transition " + from + " -> " + to);
    }

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
