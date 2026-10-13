package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import java.util.List;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Single source of truth for attribute names and table definitions (also mirrored in Terraform).
 *
 * <ul>
 *   <li>events: one item per event, carrying the per-status inventory counters.
 *   <li>tickets: one item per ticket; the sparse GSI {@code available-index} only contains AVAILABLE tickets.
 *   <li>orders: orders plus idempotency items; the sparse GSI {@code status-expiry-index} only contains
 *       orders that hold tickets, ordered by reservation deadline.
 *   <li>audit: append-only ticket state changes.
 * </ul>
 */
public final class DynamoSchema {

    public static final String ID = "id";
    public static final String NAME = "name";
    public static final String DATE = "date";
    public static final String VENUE = "venue";
    public static final String CAPACITY = "capacity";
    public static final String CREATED_AT = "createdAt";
    public static final String UPDATED_AT = "updatedAt";
    public static final String VERSION = "version";
    public static final String STATUS = "status";
    public static final String EXPIRES_AT = "expiresAt";

    public static final String EVENT_ID = "eventId";
    public static final String TICKET_ID = "ticketId";
    public static final String ORDER_ID = "orderId";
    public static final String AVAILABLE_EVENT = "availableEvent";
    public static final String AVAILABLE_INDEX = "available-index";

    public static final String CUSTOMER_ID = "customerId";
    public static final String QUANTITY = "quantity";
    public static final String TICKET_IDS = "ticketIds";
    public static final String REJECTION_REASON = "rejectionReason";
    public static final String IDEMPOTENCY_KEY = "idempotencyKey";
    public static final String IDEMPOTENCY_PREFIX = "idem#";
    public static final String STATUS_EXPIRY_INDEX = "status-expiry-index";

    public static final String TICKET_KEY = "ticketKey";
    public static final String AT = "at";
    public static final String FROM_STATUS = "fromStatus";
    public static final String TO_STATUS = "toStatus";
    public static final String REASON = "reason";

    public static final String CNT_AVAILABLE = "cntAvailable";
    public static final String CNT_RESERVED = "cntReserved";
    public static final String CNT_PENDING = "cntPending";
    public static final String CNT_SOLD = "cntSold";
    public static final String CNT_COMPLIMENTARY = "cntComplimentary";

    private DynamoSchema() {}

    public static List<CreateTableRequest> tableDefinitions(DynamoTables tables) {
        return List.of(
                CreateTableRequest.builder()
                        .tableName(tables.events())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(attr(ID, ScalarAttributeType.S))
                        .keySchema(key(ID, KeyType.HASH))
                        .build(),
                CreateTableRequest.builder()
                        .tableName(tables.tickets())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                attr(EVENT_ID, ScalarAttributeType.S),
                                attr(TICKET_ID, ScalarAttributeType.S),
                                attr(AVAILABLE_EVENT, ScalarAttributeType.S))
                        .keySchema(key(EVENT_ID, KeyType.HASH), key(TICKET_ID, KeyType.RANGE))
                        .globalSecondaryIndexes(index(AVAILABLE_INDEX, AVAILABLE_EVENT, TICKET_ID))
                        .build(),
                CreateTableRequest.builder()
                        .tableName(tables.orders())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                attr(ID, ScalarAttributeType.S),
                                attr(STATUS, ScalarAttributeType.S),
                                attr(EXPIRES_AT, ScalarAttributeType.N))
                        .keySchema(key(ID, KeyType.HASH))
                        .globalSecondaryIndexes(index(STATUS_EXPIRY_INDEX, STATUS, EXPIRES_AT))
                        .build(),
                CreateTableRequest.builder()
                        .tableName(tables.audit())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(attr(TICKET_KEY, ScalarAttributeType.S), attr(AT, ScalarAttributeType.S))
                        .keySchema(key(TICKET_KEY, KeyType.HASH), key(AT, KeyType.RANGE))
                        .build());
    }

    private static AttributeDefinition attr(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static GlobalSecondaryIndex index(String name, String hash, String range) {
        return GlobalSecondaryIndex.builder()
                .indexName(name)
                .keySchema(key(hash, KeyType.HASH), key(range, KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();
    }
}
