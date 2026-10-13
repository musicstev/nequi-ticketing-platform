package com.nequi.ticketing.domain.exception;

/**
 * Root of all business errors. Sealed so that the web layer can map every case with an exhaustive
 * pattern-matching switch and the compiler flags a newly added exception that is not handled.
 */
public abstract sealed class DomainException extends RuntimeException
        permits EventNotFoundException,
                OrderNotFoundException,
                InsufficientInventoryException,
                InvalidStateTransitionException,
                ReservationExpiredException,
                ConcurrentUpdateException,
                InvalidRequestException,
                IdempotencyConflictException,
                QueueUnavailableException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
