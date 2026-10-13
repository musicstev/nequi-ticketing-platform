package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.canceled;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.failed;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.port.OrderRepository.CreationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

class DynamoOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final DynamoOrderRepository repository = new DynamoOrderRepository(client, DynamoTables.defaults());

    private Order pending() {
        return Order.pending("o1", "e1", "c1", 2, "c1#key", NOW);
    }

    @Test
    void createStoresOrderAndIdempotencyMarkerAtomically() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(ok(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(repository.createIfAbsent(pending()))
                .expectNext(new CreationResult(pending(), true))
                .verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> tx = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(tx.capture());
        var items = tx.getValue().transactItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).put().conditionExpression()).isEqualTo("attribute_not_exists(id)");
        assertThat(items.get(1).put().item().get("id").s()).isEqualTo("idem#c1#key");
        assertThat(items.get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(id)");
    }

    @Test
    void duplicateIdempotencyKeyReturnsTheOriginalOrder() {
        Order original = pending().withVersion(2);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("None", "ConditionalCheckFailed")));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            String id = inv.getArgument(0, GetItemRequest.class).key().get("id").s();
            Map<String, AttributeValue> item = id.startsWith("idem#")
                    ? DynamoItems.idempotencyItem("c1#key", "o1")
                    : DynamoItems.orderItem(original);
            return ok(GetItemResponse.builder().item(item).build());
        });

        StepVerifier.create(repository.createIfAbsent(Order.pending("o-new", "e1", "c1", 2, "c1#key", NOW)))
                .expectNext(new CreationResult(original, false))
                .verifyComplete();
    }

    @Test
    void otherCancellationReasonsAreTransientConflicts() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("TransactionConflict", "None")));
        StepVerifier.create(repository.createIfAbsent(pending())).expectError(ConcurrentUpdateException.class).verify();
    }

    @Test
    void idempotencyMarkerPointingToMissingOrderIsAConflict() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(failed(canceled("None", "ConditionalCheckFailed")));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            String id = inv.getArgument(0, GetItemRequest.class).key().get("id").s();
            return ok(id.startsWith("idem#")
                    ? GetItemResponse.builder().item(DynamoItems.idempotencyItem("c1#key", "o1")).build()
                    : GetItemResponse.builder().build());
        });
        StepVerifier.create(repository.createIfAbsent(pending())).expectError(ConcurrentUpdateException.class).verify();
    }

    @Test
    void findByIdUsesConsistentReads() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(ok(GetItemResponse.builder().item(DynamoItems.orderItem(pending())).build()))
                .thenReturn(ok(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById("o1")).expectNext(pending()).verifyComplete();
        StepVerifier.create(repository.findById("nope")).verifyComplete();

        ArgumentCaptor<GetItemRequest> req = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client, times(2)).getItem(req.capture());
        assertThat(req.getValue().consistentRead()).isTrue();
    }

    @Test
    void updateIsAConditionalWriteOnTheVersionAndBumpsIt() {
        when(client.putItem(any(PutItemRequest.class))).thenReturn(ok(PutItemResponse.builder().build()));
        Order rejected = pending().withVersion(3).rejected("SOLD_OUT", NOW);

        StepVerifier.create(repository.update(rejected))
                .expectNextMatches(o -> o.version() == 4 && o.status() == OrderStatus.REJECTED)
                .verifyComplete();

        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(put.capture());
        assertThat(put.getValue().conditionExpression()).isEqualTo("#v = :expected");
        assertThat(put.getValue().expressionAttributeValues().get(":expected").n()).isEqualTo("3");
        assertThat(put.getValue().item().get("version").n()).isEqualTo("4");
    }

    @Test
    void updateConflictSurfacesAsConcurrentUpdate() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("stale").build()));
        StepVerifier.create(repository.update(pending())).expectError(ConcurrentUpdateException.class).verify();
    }

    @Test
    void findExpiredQueriesBothHoldStatusesUpToTheLimit() {
        Order reserved = pending().reserved(List.of("T-1", "T-2"), NOW.minusSeconds(1), NOW);
        Order awaiting = reserved.pendingConfirmation(NOW).withVersion(2);
        when(client.query(any(QueryRequest.class)))
                .thenReturn(ok(QueryResponse.builder().items(DynamoItems.orderItem(reserved)).build()))
                .thenReturn(ok(QueryResponse.builder().items(DynamoItems.orderItem(awaiting)).build()));

        StepVerifier.create(repository.findExpired(NOW, 10))
                .expectNext(reserved)
                .expectNext(awaiting)
                .verifyComplete();

        ArgumentCaptor<QueryRequest> req = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(req.capture());
        assertThat(req.getAllValues()).extracting(QueryRequest::indexName).containsOnly("status-expiry-index");
        assertThat(req.getAllValues())
                .extracting(r -> r.expressionAttributeValues().get(":status").s())
                .containsExactly("RESERVED", "PENDING_CONFIRMATION");
        assertThat(req.getAllValues().get(0).expressionAttributeValues().get(":now").n())
                .isEqualTo(Long.toString(NOW.toEpochMilli()));
    }
}
