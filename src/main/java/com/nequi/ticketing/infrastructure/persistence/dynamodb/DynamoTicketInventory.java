package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoItems.n;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoItems.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoSchema.ID;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.InsufficientInventoryException;
import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.model.TransitionReason;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Oversell protection, layered:
 *
 * <ol>
 *   <li>every ticket is its own item and changes state only through a conditional update
 *       ({@code status = :from AND version = :v}), so two buyers can never own the same ticket;
 *   <li>the event item carries per-status counters; reserving requires {@code cntAvailable >= :q}
 *       in the same transaction, so counters and tickets cannot drift apart;
 *   <li>order, tickets, counters and audit rows change in ONE {@code TransactWriteItems}: all or nothing.
 * </ol>
 *
 * Losing a race on tickets is retried with fresh random candidates (bounded, jittered backoff);
 * losing it on the counter means the event is sold out.
 */
public class DynamoTicketInventory implements TicketInventory {

    static final int MAX_CONTENTION_RETRIES = 5;
    private static final int CANDIDATE_FACTOR = 4;
    private static final int MAX_CANDIDATES = 100;
    private static final int MAX_UNPROCESSED_ATTEMPTS = 3;

    private static final int ORDER_INDEX = 0;
    private static final int RESERVE_EVENT_INDEX = 1;
    private static final int RESERVE_FIRST_TICKET_INDEX = 2;

    private final DynamoDbAsyncClient client;
    private final DynamoTables tables;
    private final Clock clock;
    private final Duration retryBackoff;

    public DynamoTicketInventory(DynamoDbAsyncClient client, DynamoTables tables, Clock clock, Duration retryBackoff) {
        this.client = client;
        this.tables = tables;
        this.clock = clock;
        this.retryBackoff = retryBackoff;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Mono<Availability> availability(String eventId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.events())
                .key(Map.of(ID, s(eventId)))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem())
                .map(response -> DynamoItems.availability(response.item()))
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)));
    }

    @Override
    public Mono<List<Ticket>> ticketsOf(Order order) {
        if (order.ticketIds().isEmpty()) {
            return Mono.just(List.of());
        }
        List<Map<String, AttributeValue>> keys = order.ticketIds().stream()
                .map(id -> DynamoItems.ticketKey(order.eventId(), id))
                .toList();
        KeysAndAttributes request =
                KeysAndAttributes.builder().keys(keys).consistentRead(true).build();
        return fetchTickets(request, 0).map(tickets -> {
            List<Ticket> sorted = new ArrayList<>(tickets);
            sorted.sort(Comparator.comparing(Ticket::ticketId));
            return List.copyOf(sorted);
        });
    }

    private Mono<List<Ticket>> fetchTickets(KeysAndAttributes keys, int attempt) {
        BatchGetItemRequest request =
                BatchGetItemRequest.builder().requestItems(Map.of(tables.tickets(), keys)).build();
        return Mono.fromFuture(() -> client.batchGetItem(request)).flatMap(response -> {
            List<Ticket> found = response.responses().getOrDefault(tables.tickets(), List.of()).stream()
                    .map(DynamoItems::ticket)
                    .toList();
            KeysAndAttributes left = response.unprocessedKeys().get(tables.tickets());
            if (left == null || left.keys().isEmpty()) {
                return Mono.just(found);
            }
            if (attempt + 1 >= MAX_UNPROCESSED_ATTEMPTS) {
                return Mono.error(new ConcurrentUpdateException("Could not read all tickets, retry"));
            }
            return Mono.delay(retryBackoff.multipliedBy(1L << attempt))
                    .then(fetchTickets(left, attempt + 1))
                    .map(more -> {
                        List<Ticket> all = new ArrayList<>(found);
                        all.addAll(more);
                        return all;
                    });
        });
    }

    // ------------------------------------------------------------- reservation

    @Override
    public Mono<Order> reserve(Order order, Instant expiresAt) {
        if (!order.status().canTransitionTo(OrderStatus.RESERVED)) {
            return Mono.error(new InvalidStateTransitionException("order", order.status(), OrderStatus.RESERVED));
        }
        return Mono.defer(() -> pickAvailable(order.eventId(), order.quantity()))
                .flatMap(candidates -> reserveTransaction(order, expiresAt, candidates))
                .retryWhen(contentionRetry());
    }

    private Mono<Order> reserveTransaction(Order order, Instant expiresAt, List<Ticket> candidates) {
        Instant now = clock.instant();
        int quantity = order.quantity();
        List<Ticket.Change> changes = candidates.stream()
                .map(t -> t.transitionTo(
                        TicketStatus.RESERVED, order.id(), expiresAt, TransitionReason.RESERVATION_REQUESTED, now))
                .toList();
        Order after = order.reserved(candidates.stream().map(Ticket::ticketId).toList(), expiresAt, now)
                .withVersion(order.version() + 1);

        List<TransactWriteItem> items = new ArrayList<>();
        items.add(orderUpdate(order, after, now));
        items.add(counterUpdate(order.eventId(), TicketStatus.AVAILABLE, TicketStatus.RESERVED, quantity, true));
        for (int i = 0; i < candidates.size(); i++) {
            items.add(ticketUpdate(candidates.get(i), changes.get(i).ticket(), true));
        }
        changes.forEach(c -> items.add(auditPut(c)));

        return transact(items).thenReturn(after).onErrorMap(TransactionCanceledException.class, e -> {
            if (DynamoErrors.conditionFailed(e, ORDER_INDEX)) {
                return new ConcurrentUpdateException("Order was modified concurrently");
            }
            if (DynamoErrors.conditionFailed(e, RESERVE_EVENT_INDEX)) {
                return new InsufficientInventoryException(order.eventId(), quantity);
            }
            return new TicketContention();
        });
    }

    @Override
    public Mono<List<Ticket>> grantComplimentary(String eventId, int quantity, Instant now) {
        return Mono.defer(() -> pickAvailable(eventId, quantity))
                .flatMap(candidates -> complimentaryTransaction(eventId, quantity, candidates, now))
                .retryWhen(contentionRetry());
    }

    private Mono<List<Ticket>> complimentaryTransaction(String eventId, int quantity, List<Ticket> candidates, Instant now) {
        List<Ticket.Change> changes = candidates.stream()
                .map(t -> t.transitionTo(
                        TicketStatus.COMPLIMENTARY, null, null, TransitionReason.COMPLIMENTARY_GRANT, now))
                .toList();

        List<TransactWriteItem> items = new ArrayList<>();
        items.add(counterUpdate(eventId, TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, quantity, true));
        for (int i = 0; i < candidates.size(); i++) {
            items.add(ticketUpdate(candidates.get(i), changes.get(i).ticket(), true));
        }
        changes.forEach(c -> items.add(auditPut(c)));

        return transact(items)
                .thenReturn(changes.stream().map(Ticket.Change::ticket).toList())
                .onErrorMap(TransactionCanceledException.class, e -> DynamoErrors.conditionFailed(e, 0)
                        ? new InsufficientInventoryException(eventId, quantity)
                        : new TicketContention());
    }

    /** Random pick among the first few available tickets: concurrent buyers rarely collide on the same ones. */
    private Mono<List<Ticket>> pickAvailable(String eventId, int quantity) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tables.tickets())
                .indexName(DynamoSchema.AVAILABLE_INDEX)
                .keyConditionExpression(DynamoSchema.AVAILABLE_EVENT + " = :event")
                .expressionAttributeValues(Map.of(":event", s(eventId)))
                .limit(Math.min(quantity * CANDIDATE_FACTOR, MAX_CANDIDATES))
                .build();
        return Mono.fromFuture(() -> client.query(request)).flatMap(response -> {
            List<Ticket> available = new ArrayList<>(
                    response.items().stream().map(DynamoItems::ticket).toList());
            if (available.size() < quantity) {
                return Mono.error(new InsufficientInventoryException(eventId, quantity));
            }
            Collections.shuffle(available);
            List<Ticket> picked = new ArrayList<>(available.subList(0, quantity));
            picked.sort(Comparator.comparing(Ticket::ticketId));
            return Mono.just(picked);
        });
    }

    private Retry contentionRetry() {
        return Retry.backoff(MAX_CONTENTION_RETRIES, retryBackoff)
                .maxBackoff(Duration.ofMillis(250))
                .jitter(0.5)
                .filter(e -> e instanceof TicketContention)
                .onRetryExhaustedThrow((spec, signal) -> new ConcurrentUpdateException("Too much contention on tickets"));
    }

    // ------------------------------------------------------------- transitions

    @Override
    public Mono<Order> markPendingConfirmation(Order order) {
        return Mono.defer(() -> transition(
                order,
                order.pendingConfirmation(clock.instant()),
                TicketStatus.RESERVED,
                TicketStatus.PENDING_CONFIRMATION,
                TransitionReason.PROCESSING_COMPLETED));
    }

    @Override
    public Mono<Order> confirmSale(Order order) {
        return Mono.defer(() -> transition(
                order,
                order.confirmed(clock.instant()),
                TicketStatus.PENDING_CONFIRMATION,
                TicketStatus.SOLD,
                TransitionReason.PURCHASE_CONFIRMED));
    }

    @Override
    public Mono<Order> release(Order order) {
        return Mono.defer(() -> {
            Order expired = order.expired(clock.instant());
            TicketStatus held = order.status().heldTicketStatus().orElseThrow();
            return transition(order, expired, held, TicketStatus.AVAILABLE, TransitionReason.RESERVATION_EXPIRED);
        });
    }

    /** The tickets' source status is implied by the order status, so no ticket reads are needed. */
    private Mono<Order> transition(Order before, Order after, TicketStatus from, TicketStatus to, TransitionReason reason) {
        Instant now = clock.instant();
        Order next = after.withVersion(before.version() + 1);
        int quantity = before.ticketIds().size();

        List<Ticket.Change> changes = new ArrayList<>();
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(orderUpdate(before, next, now));
        items.add(counterUpdate(before.eventId(), from, to, quantity, false));
        for (String ticketId : before.ticketIds()) {
            Ticket current = new Ticket(before.eventId(), ticketId, from, before.id(), before.expiresAt(), 0);
            Ticket.Change change = current.transitionTo(to, null, null, reason, now);
            changes.add(change);
            items.add(ticketUpdate(current, change.ticket(), false));
        }
        changes.forEach(c -> items.add(auditPut(c)));

        return transact(items).thenReturn(next).onErrorMap(TransactionCanceledException.class,
                e -> new ConcurrentUpdateException("Order or tickets were modified concurrently"));
    }

    // ----------------------------------------------------------- tx building

    private Mono<Void> transact(List<TransactWriteItem> items) {
        TransactWriteItemsRequest request =
                TransactWriteItemsRequest.builder().transactItems(items).build();
        return Mono.fromFuture(() -> client.transactWriteItems(request)).then();
    }

    private TransactWriteItem orderUpdate(Order before, Order after, Instant now) {
        List<String> sets = new ArrayList<>(List.of("#s = :to", "updatedAt = :now"));
        List<String> removes = new ArrayList<>();
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":to", s(after.status().name()));
        values.put(":from", s(before.status().name()));
        values.put(":now", s(now.toString()));
        values.put(":ov", n(before.version()));
        values.put(":one", n(1));
        if (!after.ticketIds().equals(before.ticketIds())) {
            sets.add(DynamoSchema.TICKET_IDS + " = :ids");
            values.put(":ids", AttributeValue.fromL(after.ticketIds().stream().map(DynamoItems::s).toList()));
        }
        if (after.expiresAt() != null && !after.expiresAt().equals(before.expiresAt())) {
            sets.add(DynamoSchema.EXPIRES_AT + " = :exp");
            values.put(":exp", n(after.expiresAt().toEpochMilli()));
        } else if (after.expiresAt() == null && before.expiresAt() != null) {
            removes.add(DynamoSchema.EXPIRES_AT);
        }
        Update update = Update.builder()
                .tableName(tables.orders())
                .key(Map.of(ID, s(before.id())))
                .updateExpression(expression(sets, removes))
                .conditionExpression("#v = :ov AND #s = :from")
                .expressionAttributeNames(Map.of("#s", DynamoSchema.STATUS, "#v", DynamoSchema.VERSION))
                .expressionAttributeValues(values)
                .build();
        return TransactWriteItem.builder().update(update).build();
    }

    private TransactWriteItem counterUpdate(String eventId, TicketStatus from, TicketStatus to, int quantity, boolean requireStock) {
        Update.Builder update = Update.builder()
                .tableName(tables.events())
                .key(Map.of(ID, s(eventId)))
                .updateExpression("ADD " + DynamoItems.counterFor(from) + " :out, " + DynamoItems.counterFor(to) + " :in");
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":out", n(-quantity));
        values.put(":in", n(quantity));
        if (requireStock) {
            update.conditionExpression(DynamoSchema.CNT_AVAILABLE + " >= :q");
            values.put(":q", n(quantity));
        }
        return TransactWriteItem.builder().update(update.expressionAttributeValues(values).build()).build();
    }

    /**
     * @param byVersion true when {@code before} was read from the table (candidate ticket): guard with its
     *     version; false when only the owning order is known: guard with status and owning order id
     */
    private TransactWriteItem ticketUpdate(Ticket before, Ticket after, boolean byVersion) {
        List<String> sets = new ArrayList<>(List.of("#s = :to"));
        List<String> removes = new ArrayList<>();
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":to", s(after.status().name()));
        values.put(":from", s(before.status().name()));
        values.put(":one", n(1));

        if (after.status() == TicketStatus.AVAILABLE) {
            sets.add(DynamoSchema.AVAILABLE_EVENT + " = :ev");
            values.put(":ev", s(after.eventId()));
            removes.add(DynamoSchema.ORDER_ID);
            removes.add(DynamoSchema.EXPIRES_AT);
        } else {
            if (before.status() == TicketStatus.AVAILABLE) {
                removes.add(DynamoSchema.AVAILABLE_EVENT);
            }
            if (after.orderId() != null && !after.orderId().equals(before.orderId())) {
                sets.add(DynamoSchema.ORDER_ID + " = :newOrder");
                values.put(":newOrder", s(after.orderId()));
            }
            if (after.expiresAt() != null && !after.expiresAt().equals(before.expiresAt())) {
                sets.add(DynamoSchema.EXPIRES_AT + " = :exp");
                values.put(":exp", n(after.expiresAt().toEpochMilli()));
            } else if (after.expiresAt() == null && before.expiresAt() != null) {
                removes.add(DynamoSchema.EXPIRES_AT);
            }
        }

        String condition;
        if (byVersion) {
            condition = "#s = :from AND #v = :tv";
            values.put(":tv", n(before.version()));
        } else {
            condition = "#s = :from AND orderId = :oid";
            values.put(":oid", s(before.orderId()));
        }
        Update update = Update.builder()
                .tableName(tables.tickets())
                .key(DynamoItems.ticketKey(before.eventId(), before.ticketId()))
                .updateExpression(expression(sets, removes))
                .conditionExpression(condition)
                .expressionAttributeNames(Map.of("#s", DynamoSchema.STATUS, "#v", DynamoSchema.VERSION))
                .expressionAttributeValues(values)
                .build();
        return TransactWriteItem.builder().update(update).build();
    }

    private TransactWriteItem auditPut(Ticket.Change change) {
        Put put = Put.builder()
                .tableName(tables.audit())
                .item(DynamoItems.auditItem(change.transition(), UUID.randomUUID().toString()))
                .build();
        return TransactWriteItem.builder().put(put).build();
    }

    private static String expression(List<String> sets, List<String> removes) {
        StringBuilder expression = new StringBuilder("SET ").append(String.join(", ", sets));
        if (!removes.isEmpty()) {
            expression.append(" REMOVE ").append(String.join(", ", removes));
        }
        return expression.append(" ADD #v :one").toString();
    }

    /** Signals that a ticket-level condition lost a race; only this is retried with fresh candidates. */
    private static final class TicketContention extends RuntimeException {

        TicketContention() {
            super("ticket contention", null, false, false);
        }
    }
}
