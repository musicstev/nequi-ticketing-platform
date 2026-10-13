package com.nequi.ticketing.application;

public record PurchaseCommand(String eventId, String customerId, int quantity, String idempotencyKey) {}
