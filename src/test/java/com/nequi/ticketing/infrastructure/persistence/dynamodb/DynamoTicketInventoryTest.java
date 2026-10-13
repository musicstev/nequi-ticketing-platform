package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.canceled;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.failed;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.InsufficientInventoryException;
import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

class DynamoTicketInventoryTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(600);

    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final DynamoTicketInventory inventory = new DynamoTicketInventory(
            client, DynamoTables.defaults(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(1));

    private Order pending(int quantity) {
        return Order.pending("o1", "e1", "c1", quantity, "c1#key", NOW);
    }

    private Order held(OrderStatus status, int quantity) {
        List<String> ids = IntStream.rangeClosed(1, quantity).mapToObj(i -> "T-%06d".formatted(i)).toList();
        Order reserved = pending(quantity).reserved(ids, EXPIRES, NOW).withVersion(1);
        return status == OrderStatus.RESERVED ? reserved : reserved.pendingConfirmation(NOW).withVersion(2);
    }

    private void availableTickets(int count) {
        List<Map<String, AttributeValue>> items = IntStream.rangeClosed(1, count)
                .mapToObj(i -> DynamoItems.ticketItem(Ticket.newAvailable("e1", i)))
                .toList();
        when(client.query(any(QueryRequest.class))).thenReturn(ok(QueryResponse.builder().items(items).build()));
    }

    private TransactWriteItemsRequest capturedTransaction() {
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        return captor.getValue();
    }

    private void transactionSucceeds() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(ok(TransactWriteItemsResponse.builder().build()));
    }

    // ---- reserve ----

    @Test
    void reserveMovesPickedTicketsToReservedWithConditionalWritesAndAudit() {
        availableTickets(6);
        transactionSucceeds();

        StepVerifier.create(inventory.reserve(pending(2), EXPIRES))
                .expectNextMatches(o -> o.status() == OrderStatus.RESERVED
                        && o.version() == 1
                        && o.ticketIds().size() == 2
                        && o.expiresAt().equals(EXPIRES))
                .verifyComplete();

        List<TransactWriteItem> items = capturedTransaction().transactItems();
        assertThat(items).hasSize(2 + 2 + 2); // order, event, 2 tickets, 2 audit rows

        var orderUpdate = items.get(0).update();
        assertThat(orderUpdate.tableName()).isEqualTo("ticketing-orders");
        assertThat(orderUpdate.conditionExpression()).contains("#v = :ov").contains("#s = :from");
        assertThat(orderUpdate.expressionAttributeValues().get(":ov").n()).isEqualTo("0");

        var eventUpdate = items.get(1).update();
        assertThat(eventUpdate.tableName()).isEqualTo("ticketing-events");
        assertThat(eventUpdate.conditionExpression()).isEqualTo("cntAvailable >= :q");
        assertThat(eventUpdate.updateExpression()).contains("cntAvailable").contains("cntReserved");

        for (TransactWriteItem ticketWrite : items.subList(2, 4)) {
            var update = ticketWrite.update();
            assertThat(update.tableName()).isEqualTo("ticketing-tickets");
            assertThat(update.conditionExpression()).contains("#s = :from").contains("#v = :tv");
            assertThat(update.updateExpression()).contains("REMOVE availableEvent");
            assertThat(update.expressionAttributeValues().get(":to").s()).isEqualTo("RESERVED");
        }
        for (TransactWriteItem auditWrite : items.subList(4, 6)) {
            assertThat(auditWrite.put().tableName()).isEqualTo("ticketing-ticket-audit");
            assertThat(auditWrite.put().item().get("toStatus").s()).isEqualTo("RESERVED");
            assertThat(auditWrite.put().item().get("reason").s()).isEqualTo("RESERVATION_REQUESTED");
        }
    }

    @Test
    void reserveFailsWithoutWritingWhenFewerTicketsThanRequestedAreAvailable() {
        availableTickets(1);
        StepVerifier.create(inventory.reserve(pending(2), EXPIRES)).expectError(InsufficientInventoryException.class).verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void reserveMapsFailedEventCounterConditionToInsufficientInventory() {
        availableTickets(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("None", "ConditionalCheckFailed", "None", "None", "None", "None")));
        StepVerifier.create(inventory.reserve(pending(2), EXPIRES)).expectError(InsufficientInventoryException.class).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void reserveRetriesWithFreshCandidatesWhenAnotherBuyerTookTheSameTicket() {
        availableTickets(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("None", "None", "ConditionalCheckFailed", "None", "None", "None")))
                .thenReturn(ok(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(inventory.reserve(pending(2), EXPIRES)).expectNextCount(1).verifyComplete();
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
        verify(client, times(2)).query(any(QueryRequest.class));
    }

    @Test
    void reserveGivesUpAfterBoundedRetriesOnPersistentContention() {
        availableTickets(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("None", "None", "ConditionalCheckFailed", "None", "None", "None")));

        StepVerifier.create(inventory.reserve(pending(2), EXPIRES)).expectError(ConcurrentUpdateException.class).verify();
        verify(client, times(1 + DynamoTicketInventory.MAX_CONTENTION_RETRIES))
                .transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void reserveDoesNotRetryWhenTheOrderItselfChangedConcurrently() {
        availableTickets(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("ConditionalCheckFailed", "None", "None", "None", "None", "None")));

        StepVerifier.create(inventory.reserve(pending(2), EXPIRES)).expectError(ConcurrentUpdateException.class).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void reserveOfNonPendingOrderIsRejectedByTheStateMachine() {
        StepVerifier.create(inventory.reserve(held(OrderStatus.RESERVED, 1), EXPIRES))
                .expectError(InvalidStateTransitionException.class)
                .verify();
        verify(client, never()).query(any(QueryRequest.class));
    }

    @Test
    void unexpectedClientFailuresPropagateUntouched() {
        availableTickets(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(failed(new IllegalStateException("boom")));
        StepVerifier.create(inventory.reserve(pending(1), EXPIRES)).expectError(IllegalStateException.class).verify();
    }

    // ---- transitions ----

    @Test
    void markPendingConfirmationAdvancesTicketsAndCounters() {
        transactionSucceeds();

        StepVerifier.create(inventory.markPendingConfirmation(held(OrderStatus.RESERVED, 2)))
                .expectNextMatches(o -> o.status() == OrderStatus.PENDING_CONFIRMATION && o.version() == 2)
                .verifyComplete();

        List<TransactWriteItem> items = capturedTransaction().transactItems();
        assertThat(items).hasSize(2 + 2 + 2);
        var eventUpdate = items.get(1).update();
        assertThat(eventUpdate.updateExpression()).contains("cntReserved").contains("cntPending");
        assertThat(eventUpdate.conditionExpression()).isNull();
        var ticketUpdate = items.get(2).update();
        assertThat(ticketUpdate.expressionAttributeValues().get(":from").s()).isEqualTo("RESERVED");
        assertThat(ticketUpdate.expressionAttributeValues().get(":to").s()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(ticketUpdate.conditionExpression()).contains("orderId = :oid");
    }

    @Test
    void confirmSaleMakesTicketsSoldAndFinal() {
        transactionSucceeds();

        StepVerifier.create(inventory.confirmSale(held(OrderStatus.PENDING_CONFIRMATION, 1)))
                .expectNextMatches(o -> o.status() == OrderStatus.CONFIRMED && o.version() == 3 && o.expiresAt() == null)
                .verifyComplete();

        List<TransactWriteItem> items = capturedTransaction().transactItems();
        assertThat(items.get(1).update().updateExpression()).contains("cntPending").contains("cntSold");
        assertThat(items.get(2).update().expressionAttributeValues().get(":to").s()).isEqualTo("SOLD");
        assertThat(items.get(2).update().updateExpression()).contains("REMOVE expiresAt");
        assertThat(items.get(3).put().item().get("reason").s()).isEqualTo("PURCHASE_CONFIRMED");
    }

    @Test
    void releaseReturnsTicketsToTheAvailablePoolFromEitherHoldStatus() {
        transactionSucceeds();
        StepVerifier.create(inventory.release(held(OrderStatus.RESERVED, 1)))
                .expectNextMatches(o -> o.status() == OrderStatus.EXPIRED)
                .verifyComplete();
        var first = capturedTransaction().transactItems();
        assertThat(first.get(1).update().updateExpression()).contains("cntReserved").contains("cntAvailable");
        assertThat(first.get(2).update().updateExpression())
                .contains("availableEvent = :ev")
                .contains("REMOVE orderId, expiresAt");
        assertThat(first.get(2).update().expressionAttributeValues().get(":from").s()).isEqualTo("RESERVED");
    }

    @Test
    void releaseOfPendingConfirmationOrderUsesThatTicketStatusAsSource() {
        transactionSucceeds();
        StepVerifier.create(inventory.release(held(OrderStatus.PENDING_CONFIRMATION, 1))).expectNextCount(1).verifyComplete();
        var items = capturedTransaction().transactItems();
        assertThat(items.get(2).update().expressionAttributeValues().get(":from").s()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(items.get(1).update().updateExpression()).contains("cntPending");
    }

    @Test
    void conflictingConfirmationVersusReleaseSurfacesAsConcurrentUpdate() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("None", "None", "ConditionalCheckFailed", "None")));
        StepVerifier.create(inventory.release(held(OrderStatus.PENDING_CONFIRMATION, 1)))
                .expectError(ConcurrentUpdateException.class)
                .verify();
    }

    @Test
    void transitionsOnOrdersThatDoNotHoldTicketsAreRejected() {
        StepVerifier.create(inventory.release(pending(1))).expectError(InvalidStateTransitionException.class).verify();
        StepVerifier.create(inventory.confirmSale(held(OrderStatus.RESERVED, 1)))
                .expectError(InvalidStateTransitionException.class)
                .verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    // ---- complimentary ----

    @Test
    void complimentaryGrantMovesAvailableTicketsToFinalComplimentaryStatus() {
        availableTickets(5);
        transactionSucceeds();

        StepVerifier.create(inventory.grantComplimentary("e1", 2, NOW))
                .expectNextMatches(tickets -> tickets.size() == 2
                        && tickets.stream().allMatch(t -> t.status() == TicketStatus.COMPLIMENTARY && t.orderId() == null))
                .verifyComplete();

        List<TransactWriteItem> items = capturedTransaction().transactItems();
        assertThat(items).hasSize(1 + 2 + 2);
        assertThat(items.get(0).update().conditionExpression()).isEqualTo("cntAvailable >= :q");
        assertThat(items.get(0).update().updateExpression()).contains("cntComplimentary");
    }

    @Test
    void complimentaryGrantFailsWhenNotEnoughTicketsRemain() {
        availableTickets(1);
        StepVerifier.create(inventory.grantComplimentary("e1", 2, NOW))
                .expectError(InsufficientInventoryException.class)
                .verify();

        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("ConditionalCheckFailed", "None", "None", "None", "None")));
        availableTickets(3);
        StepVerifier.create(inventory.grantComplimentary("e1", 2, NOW))
                .expectError(InsufficientInventoryException.class)
                .verify();
    }

    // ---- reads ----

    @Test
    void availabilityReadsLiveCountersWithConsistentRead() {
        Event event = new Event("e1", "Concert", NOW.plusSeconds(60), "Arena", 10, NOW);
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(ok(GetItemResponse.builder().item(DynamoItems.eventItem(event)).build()))
                .thenReturn(ok(GetItemResponse.builder().build()));

        StepVerifier.create(inventory.availability("e1"))
                .expectNextMatches(a -> a.available() == 10 && a.capacity() == 10)
                .verifyComplete();
        StepVerifier.create(inventory.availability("zzz")).expectError(EventNotFoundException.class).verify();

        ArgumentCaptor<GetItemRequest> req = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(2)).getItem(req.capture());
        assertThat(req.getValue().consistentRead()).isTrue();
    }

    @Test
    void ticketsOfReturnsSortedTicketStatesAndSkipsTheCallForOrdersWithoutTickets() {
        StepVerifier.create(inventory.ticketsOf(pending(1))).expectNext(List.of()).verifyComplete();
        verify(client, never()).batchGetItem(any(BatchGetItemRequest.class));

        Order order = held(OrderStatus.PENDING_CONFIRMATION, 2);
        Ticket second = new Ticket("e1", "T-000002", TicketStatus.PENDING_CONFIRMATION, "o1", EXPIRES, 2);
        Ticket first = new Ticket("e1", "T-000001", TicketStatus.PENDING_CONFIRMATION, "o1", EXPIRES, 2);
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(ok(BatchGetItemResponse.builder()
                .responses(Map.of("ticketing-tickets", List.of(DynamoItems.ticketItem(second), DynamoItems.ticketItem(first))))
                .build()));

        StepVerifier.create(inventory.ticketsOf(order)).expectNext(List.of(first, second)).verifyComplete();
    }

    @Test
    void ticketsOfRetriesUnprocessedKeys() {
        Order order = held(OrderStatus.RESERVED, 2);
        Ticket first = new Ticket("e1", "T-000001", TicketStatus.RESERVED, "o1", EXPIRES, 1);
        Ticket second = new Ticket("e1", "T-000002", TicketStatus.RESERVED, "o1", EXPIRES, 1);
        KeysAndAttributes left = KeysAndAttributes.builder()
                .keys(List.of(DynamoItems.ticketKey("e1", "T-000002")))
                .build();
        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(ok(BatchGetItemResponse.builder()
                        .responses(Map.of("ticketing-tickets", List.of(DynamoItems.ticketItem(first))))
                        .unprocessedKeys(Map.of("ticketing-tickets", left))
                        .build()))
                .thenReturn(ok(BatchGetItemResponse.builder()
                        .responses(Map.of("ticketing-tickets", List.of(DynamoItems.ticketItem(second))))
                        .build()));

        StepVerifier.create(inventory.ticketsOf(order)).expectNext(List.of(first, second)).verifyComplete();
        verify(client, times(2)).batchGetItem(any(BatchGetItemRequest.class));
    }
}
