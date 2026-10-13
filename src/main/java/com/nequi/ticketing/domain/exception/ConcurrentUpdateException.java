package com.nequi.ticketing.domain.exception;

public final class ConcurrentUpdateException extends DomainException {

    public ConcurrentUpdateException(String message) {
        super(message);
    }
}
