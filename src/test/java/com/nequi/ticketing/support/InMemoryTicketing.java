package com.nequi.ticketing.support;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.InsufficientInventoryException;
import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.model.TicketTransition;
import com.nequi.ticketing.domain.model.TransitionReason;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.OrderQueue;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.domain.port.TicketInventory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * In-memory stand-in for the DynamoDB layer. Every public operation is one serialisable "transaction"
 * (a single lock) that checks the same conditions as the real adapter: order version + status, ticket
 * status + owning order, and stock. A failed condition raises ConcurrentUpdateException, exactly like a
 * cancelled TransactWriteItems, so use-case behaviour under contention is the real thing.
 */
public class InMemoryTicketing implements TicketInventory, OrderQueue {

    private final Object lock = new Object();
    private final Clock clock;
    private final Map<String, Event> events = new HashMap<>();
    private final Map<String, Map<String, Ticket>> tickets = new HashMap<>();
    private final Map<String, Order> orders = new HashMap<>();
    private final Map<String, String> idempotency = new HashMap<>();
    private final List<TicketTransition> audit = new ArrayList<>();
    private final List<String> published = new ArrayList<>();

    public InMemoryTicketing() {
        this(Clock.systemUTC());
    }

    public InMemoryTicketing(Clock clock) {
        this.clock = clock;
    }

    private static <T> Mono<T> tx(Callable<T> body) {
        return Mono.fromCallable(body);
    }

    /** Event repository view over the shared state. */
    public EventRepository events() {
        return new EventRepository() {
            @Override
            public Mono<Event> create(Event event) {
                return createEvent(event);
            }

            @Override
            public Mono<Event> findById(String eventId) {
                return findEvent(eventId);
            }

            @Override
            public Flux<Event> findAll() {
                return findAllEvents();
            }
        };
    }

    /** Order repository view over the shared state. */
    public OrderRepository orders() {
        return new OrderRepository() {
            @Override
            public Mono<CreationResult> createIfAbsent(Order order) {
                return createOrderIfAbsent(order);
            }

            @Override
            public Mono<Order> findById(String orderId) {
                return findOrder(orderId);
            }

            @Override
            public Mono<Order> update(Order order) {
                return updateOrder(order);
            }

            @Override
            public Flux<Order> findExpired(Instant now, int limit) {
                return findExpiredOrders(now, limit);
            }
        };
    }

    // ---- inspection helpers (tests only) ----

    public Order order(String id) {
        synchronized (lock) {
            return orders.get(id);
        }
    }

    public int orderCount() {
        synchronized (lock) {
            return (int) orders.keySet().stream().filter(k -> !idempotency.containsKey(k)).count();
        }
    }

    public List<Ticket> tickets(String eventId) {
        synchronized (lock) {
            return List.copyOf(tickets.get(eventId).values());
        }
    }

    public List<TicketTransition> audit() {
        synchronized (lock) {
            return List.copyOf(audit);
        }
    }

    public List<String> published() {
        synchronized (lock) {
            return List.copyOf(published);
        }
    }

    // ---- EventRepository ----

    public Mono<Event> createEvent(Event event) {
        return tx(() -> {
            synchronized (lock) {
                if (events.putIfAbsent(event.id(), event) != null) {
                    throw new ConcurrentUpdateException("Event already exists");
                }
                Map<String, Ticket> inventory = new HashMap<>();
                IntStream.rangeClosed(1, event.capacity())
                        .mapToObj(i -> Ticket.newAvailable(event.id(), i))
                        .forEach(t -> inventory.put(t.ticketId(), t));
                tickets.put(event.id(), inventory);
                return event;
            }
        });
    }

    public Mono<Event> findEvent(String eventId) {
        return Mono.defer(() -> {
            synchronized (lock) {
                return Mono.justOrEmpty(events.get(eventId));
            }
        });
    }

    public Flux<Event> findAllEvents() {
        return Flux.defer(() -> {
            synchronized (lock) {
                return Flux.fromIterable(List.copyOf(events.values()));
            }
        });
    }

    // ---- OrderQueue ----

    @Override
    public Mono<Void> publish(String orderId) {
        return Mono.fromRunnable(() -> {
            synchronized (lock) {
                published.add(orderId);
            }
        });
    }

    // ---- OrderRepository ----

    public Mono<OrderRepository.CreationResult> createOrderIfAbsent(Order order) {
        return tx(() -> {
            synchronized (lock) {
                String existingId = idempotency.get(order.idempotencyKey());
                if (existingId != null) {
                    return new OrderRepository.CreationResult(orders.get(existingId), false);
                }
                idempotency.put(order.idempotencyKey(), order.id());
                orders.put(order.id(), order);
                return new OrderRepository.CreationResult(order, true);
            }
        });
    }

    public Mono<Order> findOrder(String orderId) {
        return Mono.defer(() -> {
            synchronized (lock) {
                return Mono.justOrEmpty(orders.get(orderId));
            }
        });
    }

    public Mono<Order> updateOrder(Order order) {
        return tx(() -> {
            synchronized (lock) {
                Order stored = orders.get(order.id());
                if (stored == null || stored.version() != order.version()) {
                    throw new ConcurrentUpdateException("Order was modified concurrently");
                }
                Order next = order.withVersion(order.version() + 1);
                orders.put(order.id(), next);
                return next;
            }
        });
    }

    public Flux<Order> findExpiredOrders(Instant now, int limit) {
        return Flux.defer(() -> {
            synchronized (lock) {
                return Flux.fromIterable(orders.values().stream()
                        .filter(o -> o.isReservationExpired(now))
                        .sorted(Comparator.comparing(Order::expiresAt))
                        .limit(limit)
                        .toList());
            }
        });
    }

    // ---- TicketInventory ----

    @Override
    public Mono<Availability> availability(String eventId) {
        return Mono.defer(() -> {
            synchronized (lock) {
                Map<TicketStatus, Long> counts = new HashMap<>();
                tickets.get(eventId).values().forEach(t -> counts.merge(t.status(), 1L, Long::sum));
                return Mono.just(new Availability(
                        eventId,
                        events.get(eventId).capacity(),
                        counts.getOrDefault(TicketStatus.AVAILABLE, 0L).intValue(),
                        counts.getOrDefault(TicketStatus.RESERVED, 0L).intValue(),
                        counts.getOrDefault(TicketStatus.PENDING_CONFIRMATION, 0L).intValue(),
                        counts.getOrDefault(TicketStatus.SOLD, 0L).intValue(),
                        counts.getOrDefault(TicketStatus.COMPLIMENTARY, 0L).intValue()));
            }
        });
    }

    @Override
    public Mono<Order> reserve(Order order, Instant expiresAt) {
        return tx(() -> {
            Instant now = clock.instant();
            synchronized (lock) {
                requireVersion(order);
                List<Ticket> candidates = tickets.get(order.eventId()).values().stream()
                        .filter(t -> t.status() == TicketStatus.AVAILABLE)
                        .sorted(Comparator.comparing(Ticket::ticketId))
                        .limit(order.quantity())
                        .toList();
                if (candidates.size() < order.quantity()) {
                    throw new InsufficientInventoryException(order.eventId(), order.quantity());
                }
                Order after = order.reserved(candidates.stream().map(Ticket::ticketId).toList(), expiresAt, now);
                candidates.forEach(t -> apply(t, TicketStatus.RESERVED, order.id(), expiresAt, TransitionReason.RESERVATION_REQUESTED, now));
                return store(after.withVersion(order.version() + 1));
            }
        });
    }

    @Override
    public Mono<Order> markPendingConfirmation(Order order) {
        return transition(order, order.pendingConfirmation(clock.instant()),
                TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, TransitionReason.PROCESSING_COMPLETED);
    }

    @Override
    public Mono<Order> confirmSale(Order order) {
        return transition(order, order.confirmed(clock.instant()), TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD,
                TransitionReason.PURCHASE_CONFIRMED);
    }

    @Override
    public Mono<Order> release(Order order) {
        return Mono.defer(() -> transition(order, order.expired(clock.instant()),
                order.status().heldTicketStatus().orElseThrow(), TicketStatus.AVAILABLE, TransitionReason.RESERVATION_EXPIRED));
    }

    @Override
    public Mono<List<Ticket>> ticketsOf(Order order) {
        return Mono.defer(() -> {
            synchronized (lock) {
                Map<String, Ticket> inventory = tickets.get(order.eventId());
                return Mono.just(order.ticketIds().stream().map(inventory::get).sorted(Comparator.comparing(Ticket::ticketId)).toList());
            }
        });
    }

    @Override
    public Mono<List<Ticket>> grantComplimentary(String eventId, int quantity, Instant now) {
        return tx(() -> {
            synchronized (lock) {
                List<Ticket> candidates = tickets.get(eventId).values().stream()
                        .filter(t -> t.status() == TicketStatus.AVAILABLE)
                        .sorted(Comparator.comparing(Ticket::ticketId))
                        .limit(quantity)
                        .toList();
                if (candidates.size() < quantity) {
                    throw new InsufficientInventoryException(eventId, quantity);
                }
                return candidates.stream()
                        .map(t -> apply(t, TicketStatus.COMPLIMENTARY, null, null, TransitionReason.COMPLIMENTARY_GRANT, now))
                        .toList();
            }
        });
    }

    // ---- internals (call with lock held) ----

    private Mono<Order> transition(Order before, Order after, TicketStatus from, TicketStatus to, TransitionReason reason) {
        return tx(() -> {
            Instant now = clock.instant();
            synchronized (lock) {
                requireVersion(before);
                Map<String, Ticket> inventory = tickets.get(before.eventId());
                for (String ticketId : before.ticketIds()) {
                    Ticket ticket = inventory.get(ticketId);
                    if (ticket.status() != from || !before.id().equals(ticket.orderId())) {
                        throw new ConcurrentUpdateException("Ticket " + ticketId + " is no longer " + from);
                    }
                }
                before.ticketIds().forEach(id -> apply(inventory.get(id), to, null, null, reason, now));
                return store(after.withVersion(before.version() + 1));
            }
        });
    }

    private Ticket apply(Ticket ticket, TicketStatus to, String orderId, Instant expiresAt, TransitionReason reason, Instant now) {
        Ticket.Change change = ticket.transitionTo(to, orderId, expiresAt, reason, now);
        tickets.get(ticket.eventId()).put(ticket.ticketId(), change.ticket());
        audit.add(change.transition());
        return change.ticket();
    }

    private Order store(Order order) {
        orders.put(order.id(), order);
        return order;
    }

    private void requireVersion(Order order) {
        Order stored = orders.get(order.id());
        if (stored == null || stored.version() != order.version() || stored.status() != order.status()) {
            throw new ConcurrentUpdateException("Order was modified concurrently");
        }
    }
}
