package com.nequi.ticketing.domain.exception;

public final class ReservationExpiredException extends DomainException {

    public ReservationExpiredException(String orderId) {
        super("Reservation expired for order " + orderId);
    }
}
