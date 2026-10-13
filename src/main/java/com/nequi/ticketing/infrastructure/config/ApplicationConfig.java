package com.nequi.ticketing.infrastructure.config;

import com.nequi.ticketing.application.ConfirmPurchaseUseCase;
import com.nequi.ticketing.application.CreateEventUseCase;
import com.nequi.ticketing.application.GetAvailabilityUseCase;
import com.nequi.ticketing.application.GetOrderStatusUseCase;
import com.nequi.ticketing.application.GrantComplimentaryUseCase;
import com.nequi.ticketing.application.ProcessOrderUseCase;
import com.nequi.ticketing.application.QueryEventsUseCase;
import com.nequi.ticketing.application.ReleaseExpiredReservationsUseCase;
import com.nequi.ticketing.application.RequestPurchaseUseCase;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.IdGenerator;
import com.nequi.ticketing.domain.port.OrderMetrics;
import com.nequi.ticketing.domain.port.OrderQueue;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import com.nequi.ticketing.infrastructure.UuidIdGenerator;
import com.nequi.ticketing.infrastructure.metrics.MicrometerOrderMetrics;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoEventRepository;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoOrderRepository;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTableBootstrap;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTables;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTicketInventory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/** Composition root: the only place where ports are bound to adapters and use cases are assembled. */
@Configuration(proxyBeanMethods = false)
class ApplicationConfig {

    private static final int MAX_COMPLIMENTARY_PER_REQUEST = 40;
    private static final Duration DYNAMO_RETRY_BACKOFF = Duration.ofMillis(25);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return new UuidIdGenerator();
    }

    @Bean
    OrderMetrics orderMetrics(MeterRegistry registry) {
        return new MicrometerOrderMetrics(registry);
    }

    @Bean
    DynamoTables dynamoTables(AppProperties props) {
        var d = props.dynamodb();
        return new DynamoTables(d.eventsTable(), d.ticketsTable(), d.ordersTable(), d.auditTable());
    }

    @Bean
    DynamoTableBootstrap dynamoTableBootstrap(DynamoDbAsyncClient client, DynamoTables tables) {
        return new DynamoTableBootstrap(client, tables);
    }

    @Bean
    EventRepository eventRepository(DynamoDbAsyncClient client, DynamoTables tables) {
        return new DynamoEventRepository(client, tables, DYNAMO_RETRY_BACKOFF);
    }

    @Bean
    OrderRepository orderRepository(DynamoDbAsyncClient client, DynamoTables tables) {
        return new DynamoOrderRepository(client, tables);
    }

    @Bean
    TicketInventory ticketInventory(DynamoDbAsyncClient client, DynamoTables tables, Clock clock) {
        return new DynamoTicketInventory(client, tables, clock, DYNAMO_RETRY_BACKOFF);
    }

    @Bean
    CreateEventUseCase createEventUseCase(EventRepository events, IdGenerator ids, Clock clock) {
        return new CreateEventUseCase(events, ids, clock);
    }

    @Bean
    QueryEventsUseCase queryEventsUseCase(EventRepository events) {
        return new QueryEventsUseCase(events);
    }

    @Bean
    GetAvailabilityUseCase getAvailabilityUseCase(EventRepository events, TicketInventory inventory) {
        return new GetAvailabilityUseCase(events, inventory);
    }

    @Bean
    RequestPurchaseUseCase requestPurchaseUseCase(
            EventRepository events, OrderRepository orders, OrderQueue queue, IdGenerator ids, Clock clock, AppProperties props) {
        return new RequestPurchaseUseCase(events, orders, queue, ids, clock, props.purchase().maxTicketsPerOrder());
    }

    @Bean
    GetOrderStatusUseCase getOrderStatusUseCase(OrderRepository orders, TicketInventory inventory) {
        return new GetOrderStatusUseCase(orders, inventory);
    }

    @Bean
    ProcessOrderUseCase processOrderUseCase(
            OrderRepository orders, TicketInventory inventory, OrderMetrics metrics, Clock clock, AppProperties props) {
        return new ProcessOrderUseCase(orders, inventory, metrics, clock, props.reservation().ttl());
    }

    @Bean
    ConfirmPurchaseUseCase confirmPurchaseUseCase(
            OrderRepository orders, TicketInventory inventory, OrderMetrics metrics, Clock clock) {
        return new ConfirmPurchaseUseCase(orders, inventory, metrics, clock);
    }

    @Bean
    ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase(
            OrderRepository orders, TicketInventory inventory, OrderMetrics metrics, Clock clock, AppProperties props) {
        return new ReleaseExpiredReservationsUseCase(orders, inventory, metrics, clock, props.reservation().releaseBatchSize());
    }

    @Bean
    GrantComplimentaryUseCase grantComplimentaryUseCase(EventRepository events, TicketInventory inventory, Clock clock) {
        return new GrantComplimentaryUseCase(events, inventory, clock, MAX_COMPLIMENTARY_PER_REQUEST);
    }
}
