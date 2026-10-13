package com.nequi.ticketing.domain.exception;

public final class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
    }
}
