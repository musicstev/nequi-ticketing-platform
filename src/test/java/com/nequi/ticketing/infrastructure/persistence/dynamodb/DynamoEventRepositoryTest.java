package com.nequi.ticketing.infrastructure.persistence.dynamodb;

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
import com.nequi.ticketing.domain.model.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

class DynamoEventRepositoryTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final DynamoEventRepository repository =
            new DynamoEventRepository(client, DynamoTables.defaults(), Duration.ofMillis(1));

    private Event event(int capacity) {
        return new Event("e1", "Concert", NOW.plusSeconds(3600), "Arena", capacity, NOW);
    }

    @Test
    void createWritesTicketsInBatchesOf25ThenTheEventItem() {
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(ok(BatchWriteItemResponse.builder().build()));
        when(client.putItem(any(PutItemRequest.class))).thenReturn(ok(PutItemResponse.builder().build()));

        StepVerifier.create(repository.create(event(60))).expectNext(event(60)).verifyComplete();

        ArgumentCaptor<BatchWriteItemRequest> batches = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
        verify(client, times(3)).batchWriteItem(batches.capture());
        assertThat(batches.getAllValues())
                .extracting(r -> r.requestItems().get("ticketing-tickets").size())
                .containsExactlyInAnyOrder(25, 25, 10);

        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(put.capture());
        assertThat(put.getValue().tableName()).isEqualTo("ticketing-events");
        assertThat(put.getValue().conditionExpression()).isEqualTo("attribute_not_exists(id)");
        assertThat(put.getValue().item().get("cntAvailable").n()).isEqualTo("60");
    }

    @Test
    void unprocessedBatchItemsAreRetried() {
        WriteRequest leftover = WriteRequest.builder().build();
        when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(ok(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of("ticketing-tickets", List.of(leftover)))
                        .build()))
                .thenReturn(ok(BatchWriteItemResponse.builder().build()));
        when(client.putItem(any(PutItemRequest.class))).thenReturn(ok(PutItemResponse.builder().build()));

        StepVerifier.create(repository.create(event(10))).expectNextCount(1).verifyComplete();
        verify(client, times(2)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void persistentlyUnprocessedItemsFailTheCreationAndNoEventIsPublished() {
        WriteRequest leftover = WriteRequest.builder().build();
        when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(ok(BatchWriteItemResponse.builder()
                        .unprocessedItems(Map.of("ticketing-tickets", List.of(leftover)))
                        .build()));

        StepVerifier.create(repository.create(event(10))).expectError(ConcurrentUpdateException.class).verify();
        verify(client, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void duplicateEventIdIsAConflict() {
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(ok(BatchWriteItemResponse.builder().build()));
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(failed(ConditionalCheckFailedException.builder().message("exists").build()));

        StepVerifier.create(repository.create(event(5))).expectError(ConcurrentUpdateException.class).verify();
    }

    @Test
    void findByIdMapsTheItemOrCompletesEmpty() {
        Map<String, AttributeValue> item = DynamoItems.eventItem(event(5));
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(ok(GetItemResponse.builder().item(item).build()))
                .thenReturn(ok(GetItemResponse.builder().build()));

        StepVerifier.create(repository.findById("e1")).expectNext(event(5)).verifyComplete();
        StepVerifier.create(repository.findById("zzz")).verifyComplete();
    }

    @Test
    void findAllFollowsPagination() {
        Map<String, AttributeValue> first = DynamoItems.eventItem(event(1));
        Map<String, AttributeValue> second = DynamoItems.eventItem(new Event("e2", "B", NOW.plusSeconds(60), "V", 2, NOW));
        when(client.scan(any(ScanRequest.class)))
                .thenReturn(ok(ScanResponse.builder()
                        .items(List.of(first))
                        .lastEvaluatedKey(Map.of("id", AttributeValue.fromS("e1")))
                        .build()))
                .thenReturn(ok(ScanResponse.builder().items(List.of(second)).build()));

        StepVerifier.create(repository.findAll())
                .expectNextMatches(e -> e.id().equals("e1"))
                .expectNextMatches(e -> e.id().equals("e2"))
                .verifyComplete();
    }
}
