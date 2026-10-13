package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoSchema.ID;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.port.EventRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public class DynamoEventRepository implements EventRepository {

    static final int BATCH_SIZE = 25; // DynamoDB BatchWriteItem limit
    static final int MAX_BATCH_ATTEMPTS = 5;
    static final int WRITE_CONCURRENCY = 4;
    static final int PAGE_SIZE = 100;
    static final int MAX_EVENTS_LISTED = 500;

    private final DynamoDbAsyncClient client;
    private final DynamoTables tables;
    private final Duration retryBackoff;

    public DynamoEventRepository(DynamoDbAsyncClient client, DynamoTables tables, Duration retryBackoff) {
        this.client = client;
        this.tables = tables;
        this.retryBackoff = retryBackoff;
    }

    /**
     * Tickets are written first and the event item last, so an event is never visible (or
     * purchasable) before its inventory is complete. A failure midway leaves unreachable tickets only.
     */
    @Override
    public Mono<Event> create(Event event) {
        List<WriteRequest> ticketWrites = IntStream.rangeClosed(1, event.capacity())
                .mapToObj(seq -> Ticket.newAvailable(event.id(), seq))
                .map(ticket -> WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(DynamoItems.ticketItem(ticket)).build())
                        .build())
                .toList();

        return Flux.fromIterable(partition(ticketWrites))
                .flatMap(batch -> writeBatch(batch, 0), WRITE_CONCURRENCY)
                .then(putEvent(event));
    }

    private Mono<Void> writeBatch(List<WriteRequest> batch, int attempt) {
        BatchWriteItemRequest request = BatchWriteItemRequest.builder()
                .requestItems(Map.of(tables.tickets(), batch))
                .build();
        return Mono.fromFuture(() -> client.batchWriteItem(request)).flatMap(response -> {
            List<WriteRequest> left = response.unprocessedItems().getOrDefault(tables.tickets(), List.of());
            if (left.isEmpty()) {
                return Mono.empty();
            }
            if (attempt + 1 >= MAX_BATCH_ATTEMPTS) {
                return Mono.error(new ConcurrentUpdateException("Could not write the full ticket inventory"));
            }
            return Mono.delay(retryBackoff.multipliedBy(1L << attempt)).then(writeBatch(left, attempt + 1));
        });
    }

    private Mono<Event> putEvent(Event event) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tables.events())
                .item(DynamoItems.eventItem(event))
                .conditionExpression("attribute_not_exists(" + ID + ")")
                .build();
        return DynamoErrors.conflictOnFailedCondition(
                        Mono.fromFuture(() -> client.putItem(request)), "Event already exists")
                .thenReturn(event);
    }

    @Override
    public Mono<Event> findById(String eventId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.events())
                .key(Map.of(ID, DynamoItems.s(eventId)))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> client.getItem(request))
                .filter(response -> response.hasItem())
                .map(response -> DynamoItems.event(response.item()));
    }

    @Override
    public Flux<Event> findAll() {
        return scanPage(null)
                .expand(page -> page.hasLastEvaluatedKey() ? scanPage(page.lastEvaluatedKey()) : Mono.empty())
                .flatMapIterable(ScanResponse::items)
                .map(DynamoItems::event)
                .take(MAX_EVENTS_LISTED);
    }

    private Mono<ScanResponse> scanPage(Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> startKey) {
        ScanRequest.Builder request = ScanRequest.builder().tableName(tables.events()).limit(PAGE_SIZE);
        if (startKey != null) {
            request.exclusiveStartKey(startKey);
        }
        ScanRequest built = request.build();
        return Mono.fromFuture(() -> client.scan(built));
    }

    private static List<List<WriteRequest>> partition(List<WriteRequest> all) {
        List<List<WriteRequest>> batches = new ArrayList<>();
        for (int i = 0; i < all.size(); i += BATCH_SIZE) {
            batches.add(all.subList(i, Math.min(i + BATCH_SIZE, all.size())));
        }
        return batches;
    }
}
