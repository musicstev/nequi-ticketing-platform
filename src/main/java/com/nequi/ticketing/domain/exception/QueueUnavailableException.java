package com.nequi.ticketing.domain.exception;

public final class QueueUnavailableException extends DomainException {

    public QueueUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
