package com.nequi.ticketing.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.application.ConfirmPurchaseUseCase;
import com.nequi.ticketing.application.CreateEventCommand;
import com.nequi.ticketing.application.CreateEventUseCase;
import com.nequi.ticketing.application.GetAvailabilityUseCase;
import com.nequi.ticketing.application.GrantComplimentaryUseCase;
import com.nequi.ticketing.application.ProcessOrderUseCase;
import com.nequi.ticketing.application.PurchaseCommand;
import com.nequi.ticketing.application.ReleaseExpiredReservationsUseCase;
import com.nequi.ticketing.application.RequestPurchaseUseCase;
import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.ReservationExpiredException;
import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.model.TicketTransition;
import com.nequi.ticketing.support.InMemoryTicketing;
import com.nequi.ticketing.support.MutableClock;
import com.nequi.ticketing.support.RecordingMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Exercises the real use cases against an in-memory fake with the same conditional-write semantics as the
 * DynamoDB adapter, under genuine parallelism (Reactor parallel scheduler).
 */
class TicketingConcurrencyTest {

    private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int PARALLELISM = 16;

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryTicketing store = new InMemoryTicketing();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final AtomicLong sequence = new AtomicLong();

    private final CreateEventUseCase createEvent = new CreateEventUseCase(store.events(), () -> "evt-" + sequence.incrementAndGet(), clock);
    private final RequestPurchaseUseCase purchase =
            new RequestPurchaseUseCase(store.events(), store.orders(), store, () -> "ord-" + sequence.incrementAndGet(), clock, 10);
    private final ProcessOrderUseCase process = new ProcessOrderUseCase(store.orders(), store, metrics, clock, TTL);
    private final ConfirmPurchaseUseCase confirm = new ConfirmPurchaseUseCase(store.orders(), store, metrics, clock);
    private final ReleaseExpiredReservationsUseCase release = new ReleaseExpiredReservationsUseCase(store.orders(), store, metrics, clock, 500);
    private final GetAvailabilityUseCase availability = new GetAvailabilityUseCase(store.events(), store);
    private final GrantComplimentaryUseCase complimentary = new GrantComplimentaryUseCase(store.events(), store, clock, 40);

    private String eventWithCapacity(int capacity) {
        return createEvent.execute(new CreateEventCommand("Concert", START.plus(Duration.ofDays(30)), "Arena", capacity))
                .block()
                .id();
    }

    private int round;

    private List<String> placeOrders(String eventId, int customers, int quantity) {
        String namespace = "r" + (round++) + "-";
        return Flux.range(0, customers)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(i -> purchase.execute(new PurchaseCommand(eventId, namespace + "customer-" + i, quantity, "key-" + i)))
                .sequential()
                .map(Order::id)
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    /** Same retry the SQS listener applies to optimistic-lock conflicts. */
    private Mono<Order> consume(String orderId) {
        return process.execute(orderId).retryWhen(Retry.max(5).filter(ConcurrentUpdateException.class::isInstance));
    }

    private void processInParallel(List<String> orderIds) {
        Flux.fromIterable(orderIds)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(this::consume)
                .sequential()
                .blockLast(Duration.ofSeconds(30));
    }

    private Availability availabilityOf(String eventId) {
        return availability.execute(eventId).block();
    }

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    @Test
    void twoHundredParallelBuyersNeverSellMoreThanTheFiftyTicketsOfTheEvent() {
        String eventId = eventWithCapacity(50);

        List<String> orderIds = placeOrders(eventId, 200, 1);
        assertThat(orderIds).hasSize(200).doesNotHaveDuplicates();
        processInParallel(orderIds);

        List<Order> orders = orderIds.stream().map(id -> store.order(id)).toList();
        Map<OrderStatus, Long> byStatus = orders.stream().collect(Collectors.groupingBy(Order::status, Collectors.counting()));
        assertThat(byStatus.get(OrderStatus.PENDING_CONFIRMATION)).isEqualTo(50);
        assertThat(byStatus.get(OrderStatus.REJECTED)).isEqualTo(150);

        Availability held = availabilityOf(eventId);
        assertThat(held.available()).isZero();
        assertThat(held.pendingConfirmation()).isEqualTo(50);
        assertThat(held.sold()).isZero(); // holds are not sales

        // Every winner confirms: exactly 50 tickets are SOLD, never more.
        orders.stream()
                .filter(o -> o.status() == OrderStatus.PENDING_CONFIRMATION)
                .map(Order::id)
                .forEach(id -> confirm.execute(id).block());
        Availability finalState = availabilityOf(eventId);
        assertThat(finalState.sold()).isEqualTo(50);
        assertThat(finalState.available()).isZero();
        assertThat(store.tickets(eventId)).filteredOn(t -> t.status() == TicketStatus.SOLD).hasSize(50);
    }

    @RepeatedTest(5)
    void mixedQuantitiesNeverAssignATicketTwiceNorExceedCapacity() {
        String eventId = eventWithCapacity(50);
        List<String> orderIds = Flux.range(0, 120)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(i -> purchase.execute(
                        new PurchaseCommand(eventId, "customer-" + i, 1 + (i * 7) % 4, "key-" + i)))
                .sequential()
                .map(Order::id)
                .collectList()
                .block(Duration.ofSeconds(30));
        processInParallel(orderIds);

        List<Order> accepted = orderIds.stream()
                .map(store::order)
                .filter(o -> o.status() == OrderStatus.PENDING_CONFIRMATION)
                .toList();
        List<String> assigned = accepted.stream().flatMap(o -> o.ticketIds().stream()).toList();
        Set<String> unique = new HashSet<>(assigned);

        assertThat(unique).as("no ticket belongs to two orders").hasSameSizeAs(assigned);
        assertThat(assigned.size()).isLessThanOrEqualTo(50);
        Availability a = availabilityOf(eventId);
        assertThat(a.available() + a.pendingConfirmation()).isEqualTo(50);
        assertThat(a.pendingConfirmation()).isEqualTo(assigned.size());
        assertThat(accepted.stream().mapToInt(Order::quantity).sum()).isEqualTo(assigned.size());
    }

    @Test
    void complimentaryTicketsAreFinalAndReduceWhatCanBeSold() {
        String eventId = eventWithCapacity(50);
        List<Ticket> gifted = complimentary.execute(eventId, 5).block();
        assertThat(gifted).hasSize(5).allMatch(t -> t.status() == TicketStatus.COMPLIMENTARY);

        processInParallel(placeOrders(eventId, 100, 1));

        Availability a = availabilityOf(eventId);
        assertThat(a.complimentary()).isEqualTo(5);
        assertThat(a.pendingConfirmation()).isEqualTo(45);
        assertThat(a.sold()).isZero(); // complimentary is not an accountable sale
        assertThat(a.available()).isZero();
        assertThat(store.tickets(eventId)).filteredOn(t -> t.status() == TicketStatus.COMPLIMENTARY).hasSize(5);
    }

    @Test
    void redeliveredAndConcurrentDuplicateMessagesReserveTheTicketsExactlyOnce() {
        String eventId = eventWithCapacity(20);
        String orderId = placeOrders(eventId, 1, 3).getFirst();

        Flux.range(0, 8)
                .parallel(8)
                .runOn(Schedulers.parallel())
                .flatMap(i -> consume(orderId))
                .sequential()
                .blockLast(Duration.ofSeconds(10));
        // A late redelivery after completion changes nothing either.
        process.execute(orderId).block();
        process.execute(orderId).block();

        Order order = store.order(orderId);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.ticketIds()).hasSize(3);
        Availability a = availabilityOf(eventId);
        assertThat(a.pendingConfirmation()).isEqualTo(3);
        assertThat(a.available()).isEqualTo(17);
        assertThat(metrics.processed()).isEqualTo(1);
        assertThat(store.audit()).filteredOn(t -> t.to() == TicketStatus.RESERVED).hasSize(3);
    }

    @Test
    void duplicatePurchaseRequestsWithTheSameKeyCreateASingleOrder() {
        String eventId = eventWithCapacity(10);

        List<String> ids = Flux.range(0, 25)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(i -> purchase.execute(new PurchaseCommand(eventId, "same-customer", 2, "same-key")))
                .sequential()
                .map(Order::id)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(store.orderCount()).isEqualTo(1);
    }

    @Test
    void reservationsExpireAfterTenMinutesAndTicketsReturnToTheAvailablePool() {
        String eventId = eventWithCapacity(10);
        List<String> orderIds = placeOrders(eventId, 3, 2);
        processInParallel(orderIds);
        assertThat(availabilityOf(eventId).available()).isEqualTo(4);

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(release.execute().block()).isZero();
        assertThat(availabilityOf(eventId).available()).isEqualTo(4);

        clock.advance(Duration.ofSeconds(1)); // exactly 10 minutes after the reservation
        assertThat(release.execute().block()).isEqualTo(3);

        Availability a = availabilityOf(eventId);
        assertThat(a.available()).isEqualTo(10);
        assertThat(a.pendingConfirmation()).isZero();
        assertThat(orderIds).allSatisfy(id -> assertThat(store.order(id).status()).isEqualTo(OrderStatus.EXPIRED));
        assertThat(metrics.expired()).isEqualTo(3);
        assertThat(store.audit()).filteredOn(t -> t.to() == TicketStatus.AVAILABLE).hasSize(6);

        // The released tickets can be sold again and a late confirmation of the old order is refused.
        assertThatThrownBy(() -> confirm.execute(orderIds.getFirst()).block()).isInstanceOf(ReservationExpiredException.class);
        processInParallel(placeOrders(eventId, 10, 1));
        assertThat(availabilityOf(eventId).pendingConfirmation()).isEqualTo(10);
    }

    @Test
    void confirmedSalesAreIrreversibleEvenAfterTheDeadline() {
        String eventId = eventWithCapacity(10);
        List<String> orderIds = placeOrders(eventId, 2, 1);
        processInParallel(orderIds);
        confirm.execute(orderIds.getFirst()).block();

        clock.advance(Duration.ofHours(1));
        assertThat(release.execute().block()).isEqualTo(1); // only the unconfirmed order

        assertThat(store.order(orderIds.getFirst()).status()).isEqualTo(OrderStatus.CONFIRMED);
        Availability a = availabilityOf(eventId);
        assertThat(a.sold()).isEqualTo(1);
        assertThat(a.available()).isEqualTo(9);
    }

    @RepeatedTest(5)
    void confirmationAndExpiryRacingOnTheSameOrderHaveExactlyOneWinner() {
        String eventId = eventWithCapacity(100);
        List<String> orderIds = placeOrders(eventId, 60, 1);
        processInParallel(orderIds);
        List<Order> held = orderIds.stream().map(store::order).toList();

        List<Boolean> confirmedWon = Flux.fromIterable(held)
                .parallel(PARALLELISM)
                .runOn(Schedulers.parallel())
                .flatMap(order -> Mono.zip(
                                store.confirmSale(order).subscribeOn(Schedulers.parallel()).map(o -> true)
                                        .onErrorReturn(ConcurrentUpdateException.class, false),
                                store.release(order).subscribeOn(Schedulers.parallel()).map(o -> true)
                                        .onErrorReturn(ConcurrentUpdateException.class, false))
                        .map(pair -> {
                            assertThat(pair.getT1() ^ pair.getT2()).as("exactly one of confirm/release wins").isTrue();
                            return pair.getT1();
                        }))
                .sequential()
                .collectList()
                .block(Duration.ofSeconds(30));

        long sold = confirmedWon.stream().filter(b -> b).count();
        Availability a = availabilityOf(eventId);
        assertThat(a.sold()).isEqualTo((int) sold);
        assertThat(a.available()).isEqualTo(100 - (int) sold);
        assertThat(a.pendingConfirmation()).isZero();
        // Tickets and counters agree: nothing is sold AND available.
        assertThat(store.tickets(eventId)).filteredOn(t -> t.status() == TicketStatus.SOLD).hasSize((int) sold);
    }

    @Test
    void everyTicketTransitionIsAudited() {
        String eventId = eventWithCapacity(5);
        String orderId = placeOrders(eventId, 1, 1).getFirst();
        processInParallel(List.of(orderId));
        confirm.execute(orderId).block();

        String ticketId = store.order(orderId).ticketIds().getFirst();
        List<TicketTransition> trail = store.audit().stream().filter(t -> t.ticketId().equals(ticketId)).toList();
        List<TicketStatus> path = new ArrayList<>();
        trail.forEach(t -> path.add(t.to()));
        assertThat(path).containsExactly(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD);
        assertThat(trail).allSatisfy(t -> assertThat(t.orderId()).isEqualTo(orderId));
    }
}
