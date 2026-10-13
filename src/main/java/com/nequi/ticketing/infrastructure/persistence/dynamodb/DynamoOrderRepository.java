package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoItems.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoItems.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoSchema.ID;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.port.OrderRepository;
import java.time.Instant;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public class DynamoOrderRepository implements OrderRepository {

    private static final String NOT_EXISTS = "attribute_not_exists(" + ID + ")";

    private final DynamoDbAsyncClient client;
    private final DynamoTables tables;

    public DynamoOrderRepository(DynamoDbAsyncClient client, DynamoTables tables) {
        this.client = client;
        this.tables = tables;
    }

    /**
     * The order and its idempotency marker are written in one transaction; the marker's uniqueness
     * condition is what makes concurrent duplicate requests collapse into a single order.
     */
    @Override
    public Mono<CreationResult> createIfAbsent(Order order) {
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder()
                                .put(Put.builder()
                                        .tableName(tables.orders())
                                        .item(DynamoItems.orderItem(order))
                                        .conditionExpression(NOT_EXISTS)
                                        .build())
                                .build(),
                        TransactWriteItem.builder()
                                .put(Put.builder()
                                        .tableName(tables.orders())
                                        .item(DynamoItems.idempotencyItem(order.idempotencyKey(), order.id()))
                                        .conditionExpression(NOT_EXISTS)
                                        .build())
                                .build())
                .build();
        return Mono.fromFuture(() -> client.transactWriteItems(request))
                .thenReturn(new CreationResult(order, true))
                .onErrorResume(TransactionCanceledException.class, e -> DynamoErrors.conditionFailed(e, 1)
                        ? existingOrder(order.idempotencyKey()).map(existing -> new CreationResult(existing, false))
                        : Mono.error(new ConcurrentUpdateException("Order could not be stored, retry")));
    }

    private Mono<Order> existingOrder(String scopedKey) {
        return getItem(DynamoSchema.IDEMPOTENCY_PREFIX + scopedKey)
                .map(marker -> marker.get(DynamoSchema.ORDER_ID).s())
                .flatMap(this::findById)
                .switchIfEmpty(Mono.error(new ConcurrentUpdateException("Idempotent order not yet readable, retry")));
    }

    private Mono<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> getItem(String id) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.orders())
                .key(Map.of(ID, s(id)))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem())
                .map(response -> response.item());
    }

    @Override
    public Mono<Order> findById(String orderId) {
        return getItem(orderId).map(DynamoItems::order);
    }

    @Override
    public Mono<Order> update(Order order) {
        Order next = order.withVersion(order.version() + 1);
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tables.orders())
                .item(DynamoItems.orderItem(next))
                .conditionExpression("#v = :expected")
                .expressionAttributeNames(Map.of("#v", DynamoSchema.VERSION))
                .expressionAttributeValues(Map.of(":expected", n(order.version())))
                .build();
        return DynamoErrors.conflictOnFailedCondition(
                        Mono.fromFuture(() -> client.putItem(request)), "Order was modified concurrently")
                .thenReturn(next);
    }

    @Override
    public Flux<Order> findExpired(Instant now, int limit) {
        return Flux.just(OrderStatus.RESERVED, OrderStatus.PENDING_CONFIRMATION)
                .concatMap(status -> queryExpired(status, now, limit))
                .take(limit);
    }

    private Flux<Order> queryExpired(OrderStatus status, Instant now, int limit) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tables.orders())
                .indexName(DynamoSchema.STATUS_EXPIRY_INDEX)
                .keyConditionExpression("#s = :status AND " + DynamoSchema.EXPIRES_AT + " <= :now")
                .expressionAttributeNames(Map.of("#s", DynamoSchema.STATUS))
                .expressionAttributeValues(Map.of(":status", s(status.name()), ":now", n(now.toEpochMilli())))
                .limit(limit)
                .build();
        return Mono.fromFuture(() -> client.query(request))
                .flatMapIterable(response -> response.items())
                .map(DynamoItems::order);
    }
}
