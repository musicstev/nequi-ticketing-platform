package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.IdempotencyConflictException;
import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.IdGenerator;
import com.nequi.ticketing.domain.port.OrderQueue;
import com.nequi.ticketing.domain.port.OrderRepository;
import java.time.Clock;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/**
 * Accepts a purchase: validates, stores a PENDING order (idempotent per customer + key), enqueues it
 * immediately and returns. Heavy lifting happens in the asynchronous consumer.
 */
public class RequestPurchaseUseCase {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_CUSTOMER_ID_LENGTH = 64;

    private final EventRepository events;
    private final OrderRepository orders;
    private final OrderQueue queue;
    private final IdGenerator ids;
    private final Clock clock;
    private final int maxTicketsPerOrder;

    public RequestPurchaseUseCase(
            EventRepository events,
            OrderRepository orders,
            OrderQueue queue,
            IdGenerator ids,
            Clock clock,
            int maxTicketsPerOrder) {
        this.events = events;
        this.orders = orders;
        this.queue = queue;
        this.ids = ids;
        this.clock = clock;
        this.maxTicketsPerOrder = maxTicketsPerOrder;
    }

    public Mono<Order> execute(PurchaseCommand command) {
        String validationError = validate(command);
        if (validationError != null) {
            return Mono.error(new InvalidRequestException(validationError));
        }
        return events.findById(command.eventId())
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(command.eventId())))
                .then(Mono.defer(() -> store(command)))
                .flatMap(this::enqueueIfPending);
    }

    private Mono<Order> store(PurchaseCommand command) {
        // The key is scoped by customer so one customer can never replay or probe another's orders.
        String scopedKey = command.customerId() + "#" + command.idempotencyKey();
        Order candidate = Order.pending(
                ids.next(), command.eventId(), command.customerId(), command.quantity(), scopedKey, clock.instant());
        return orders.createIfAbsent(candidate).flatMap(result -> {
            Order stored = result.order();
            boolean sameRequest = stored.eventId().equals(command.eventId()) && stored.quantity() == command.quantity();
            return sameRequest
                    ? Mono.just(stored)
                    : Mono.error(new IdempotencyConflictException(command.idempotencyKey()));
        });
    }

    /**
     * A still-PENDING order is (re)published: the consumer is idempotent, so republishing after a
     * lost message or a failed first publish is safe and keeps the same orderId.
     */
    private Mono<Order> enqueueIfPending(Order order) {
        return order.status() == OrderStatus.PENDING ? queue.publish(order.id()).thenReturn(order) : Mono.just(order);
    }

    private String validate(PurchaseCommand c) {
        if (c.eventId() == null || c.eventId().isBlank()) {
            return "eventId is required";
        }
        if (c.customerId() == null || c.customerId().isBlank() || c.customerId().length() > MAX_CUSTOMER_ID_LENGTH) {
            return "customerId is required (max " + MAX_CUSTOMER_ID_LENGTH + " characters)";
        }
        if (c.quantity() < 1 || c.quantity() > maxTicketsPerOrder) {
            return "quantity must be between 1 and " + maxTicketsPerOrder;
        }
        if (c.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(c.idempotencyKey()).matches()) {
            return "Idempotency-Key must match [A-Za-z0-9._-]{1,64}";
        }
        return null;
    }
}
