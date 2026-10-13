package com.nequi.ticketing.domain.exception;

public final class InvalidRequestException extends DomainException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
