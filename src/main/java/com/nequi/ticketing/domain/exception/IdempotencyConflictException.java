package com.nequi.ticketing.domain.exception;

public final class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException(String key) {
        super("Idempotency key reused with a different request");
    }
}
