package com.nequi.ticketing.infrastructure.persistence.dynamodb;

/** Physical table names (configurable per environment). */
public record DynamoTables(String events, String tickets, String orders, String audit) {

    public static DynamoTables defaults() {
        return new DynamoTables("ticketing-events", "ticketing-tickets", "ticketing-orders", "ticketing-ticket-audit");
    }
}
