package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoSchema.*;

import com.nequi.ticketing.domain.model.Availability;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.model.TicketTransition;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Pure mapping between domain objects and DynamoDB items. */
public final class DynamoItems {

    private DynamoItems() {}

    public static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    public static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }

    static long num(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.n() == null ? 0 : Long.parseLong(v.n());
    }

    static Instant millis(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.n() == null ? null : Instant.ofEpochMilli(Long.parseLong(v.n()));
    }

    // ---- events ----

    public static Map<String, AttributeValue> eventItem(Event e) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ID, s(e.id()));
        item.put(NAME, s(e.name()));
        item.put(DATE, s(e.date().toString()));
        item.put(VENUE, s(e.venue()));
        item.put(CAPACITY, n(e.capacity()));
        item.put(CREATED_AT, s(e.createdAt().toString()));
        item.put(CNT_AVAILABLE, n(e.capacity()));
        item.put(CNT_RESERVED, n(0));
        item.put(CNT_PENDING, n(0));
        item.put(CNT_SOLD, n(0));
        item.put(CNT_COMPLIMENTARY, n(0));
        return item;
    }

    public static Event event(Map<String, AttributeValue> item) {
        return new Event(
                str(item, ID),
                str(item, NAME),
                Instant.parse(str(item, DATE)),
                str(item, VENUE),
                (int) num(item, CAPACITY),
                Instant.parse(str(item, CREATED_AT)));
    }

    public static Availability availability(Map<String, AttributeValue> item) {
        return new Availability(
                str(item, ID),
                (int) num(item, CAPACITY),
                (int) num(item, CNT_AVAILABLE),
                (int) num(item, CNT_RESERVED),
                (int) num(item, CNT_PENDING),
                (int) num(item, CNT_SOLD),
                (int) num(item, CNT_COMPLIMENTARY));
    }

    /** Counter attribute that tracks the number of tickets in the given status. */
    public static String counterFor(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> CNT_AVAILABLE;
            case RESERVED -> CNT_RESERVED;
            case PENDING_CONFIRMATION -> CNT_PENDING;
            case SOLD -> CNT_SOLD;
            case COMPLIMENTARY -> CNT_COMPLIMENTARY;
        };
    }

    // ---- tickets ----

    public static Map<String, AttributeValue> ticketItem(Ticket t) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(EVENT_ID, s(t.eventId()));
        item.put(TICKET_ID, s(t.ticketId()));
        item.put(STATUS, s(t.status().name()));
        item.put(VERSION, n(t.version()));
        if (t.status() == TicketStatus.AVAILABLE) {
            item.put(AVAILABLE_EVENT, s(t.eventId()));
        }
        if (t.orderId() != null) {
            item.put(ORDER_ID, s(t.orderId()));
        }
        if (t.expiresAt() != null) {
            item.put(EXPIRES_AT, n(t.expiresAt().toEpochMilli()));
        }
        return item;
    }

    public static Ticket ticket(Map<String, AttributeValue> item) {
        return new Ticket(
                str(item, EVENT_ID),
                str(item, TICKET_ID),
                TicketStatus.valueOf(str(item, STATUS)),
                str(item, ORDER_ID),
                millis(item, EXPIRES_AT),
                num(item, VERSION));
    }

    public static Map<String, AttributeValue> ticketKey(String eventId, String ticketId) {
        return Map.of(EVENT_ID, s(eventId), TICKET_ID, s(ticketId));
    }

    public static Map<String, AttributeValue> auditItem(TicketTransition t, String uniqueSuffix) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(TICKET_KEY, s(t.eventId() + "#" + t.ticketId()));
        // Fixed-width epoch millis keeps lexicographic order equal to chronological order.
        item.put(AT, s("%015d#%s".formatted(t.at().toEpochMilli(), uniqueSuffix)));
        item.put(EVENT_ID, s(t.eventId()));
        item.put(FROM_STATUS, s(t.from().name()));
        item.put(TO_STATUS, s(t.to().name()));
        item.put(REASON, s(t.reason().name()));
        if (t.orderId() != null) {
            item.put(ORDER_ID, s(t.orderId()));
        }
        return item;
    }

    // ---- orders ----

    public static Map<String, AttributeValue> orderItem(Order o) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ID, s(o.id()));
        item.put(EVENT_ID, s(o.eventId()));
        item.put(CUSTOMER_ID, s(o.customerId()));
        item.put(QUANTITY, n(o.quantity()));
        item.put(STATUS, s(o.status().name()));
        item.put(TICKET_IDS, AttributeValue.fromL(o.ticketIds().stream().map(DynamoItems::s).toList()));
        item.put(IDEMPOTENCY_KEY, s(o.idempotencyKey()));
        item.put(CREATED_AT, s(o.createdAt().toString()));
        item.put(UPDATED_AT, s(o.updatedAt().toString()));
        item.put(VERSION, n(o.version()));
        if (o.expiresAt() != null) {
            item.put(EXPIRES_AT, n(o.expiresAt().toEpochMilli()));
        }
        if (o.rejectionReason() != null) {
            item.put(REJECTION_REASON, s(o.rejectionReason()));
        }
        return item;
    }

    public static Order order(Map<String, AttributeValue> item) {
        List<String> ticketIds = item.containsKey(TICKET_IDS)
                ? item.get(TICKET_IDS).l().stream().map(AttributeValue::s).toList()
                : List.of();
        return new Order(
                str(item, ID),
                str(item, EVENT_ID),
                str(item, CUSTOMER_ID),
                (int) num(item, QUANTITY),
                OrderStatus.valueOf(str(item, STATUS)),
                ticketIds,
                millis(item, EXPIRES_AT),
                str(item, REJECTION_REASON),
                str(item, IDEMPOTENCY_KEY),
                Instant.parse(str(item, CREATED_AT)),
                Instant.parse(str(item, UPDATED_AT)),
                num(item, VERSION));
    }

    public static Map<String, AttributeValue> idempotencyItem(String scopedKey, String orderId) {
        return Map.of(ID, s(IDEMPOTENCY_PREFIX + scopedKey), ORDER_ID, s(orderId));
    }
}
