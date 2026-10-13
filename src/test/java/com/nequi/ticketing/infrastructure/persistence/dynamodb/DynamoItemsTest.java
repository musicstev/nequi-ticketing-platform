package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.model.TicketTransition;
import com.nequi.ticketing.domain.model.TransitionReason;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;

class DynamoItemsTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    @Test
    void eventRoundTripsAndStartsWithAllTicketsAvailable() {
        Event event = new Event("e1", "Concert", NOW.plusSeconds(3600), "Arena", 120, NOW);
        var item = DynamoItems.eventItem(event);

        assertThat(DynamoItems.event(item)).isEqualTo(event);
        var availability = DynamoItems.availability(item);
        assertThat(availability.available()).isEqualTo(120);
        assertThat(availability.sold()).isZero();
    }

    @Test
    void ticketRoundTripKeepsSparseIndexAttributeOnlyWhileAvailable() {
        Ticket available = Ticket.newAvailable("e1", 3);
        assertThat(DynamoItems.ticketItem(available)).containsKey(DynamoSchema.AVAILABLE_EVENT);
        assertThat(DynamoItems.ticket(DynamoItems.ticketItem(available))).isEqualTo(available);

        Ticket reserved = new Ticket("e1", "T-000003", TicketStatus.RESERVED, "o1", NOW, 2);
        var item = DynamoItems.ticketItem(reserved);
        assertThat(item).doesNotContainKey(DynamoSchema.AVAILABLE_EVENT);
        assertThat(DynamoItems.ticket(item)).isEqualTo(reserved);
    }

    @Test
    void orderRoundTripWithAndWithoutOptionalFields() {
        Order pending = Order.pending("o1", "e1", "c1", 2, "c1#key", NOW);
        assertThat(DynamoItems.order(DynamoItems.orderItem(pending))).isEqualTo(pending);

        Order held = pending.reserved(List.of("T-1", "T-2"), NOW.plusSeconds(600), NOW).withVersion(4);
        assertThat(DynamoItems.order(DynamoItems.orderItem(held))).isEqualTo(held);

        Order rejected = pending.rejected("SOLD_OUT", NOW);
        assertThat(DynamoItems.order(DynamoItems.orderItem(rejected))).isEqualTo(rejected);
    }

    @Test
    void orderWithoutTicketListIsReadAsEmpty() {
        var item = new HashMap<>(DynamoItems.orderItem(Order.pending("o1", "e1", "c1", 1, "k", NOW)));
        item.remove(DynamoSchema.TICKET_IDS);
        assertThat(DynamoItems.order(item).ticketIds()).isEmpty();
    }

    @Test
    void auditItemsSortChronologicallyAndDoNotLeakPersonalData() {
        TicketTransition t = new TicketTransition("e1", "T-1", "o1", TicketStatus.AVAILABLE, TicketStatus.RESERVED,
                TransitionReason.RESERVATION_REQUESTED, NOW);
        var item = DynamoItems.auditItem(t, "u1");
        assertThat(item.get(DynamoSchema.TICKET_KEY).s()).isEqualTo("e1#T-1");
        assertThat(item.get(DynamoSchema.AT).s()).isEqualTo("%015d#u1".formatted(NOW.toEpochMilli()));
        assertThat(item.get(DynamoSchema.REASON).s()).isEqualTo("RESERVATION_REQUESTED");
        assertThat(item).doesNotContainKey(DynamoSchema.CUSTOMER_ID);

        TicketTransition noOrder = new TicketTransition("e1", "T-1", null, TicketStatus.AVAILABLE,
                TicketStatus.COMPLIMENTARY, TransitionReason.COMPLIMENTARY_GRANT, NOW);
        assertThat(DynamoItems.auditItem(noOrder, "u2")).doesNotContainKey(DynamoSchema.ORDER_ID);
    }

    @Test
    void everyTicketStatusHasItsOwnCounter() {
        assertThat(Arrays.stream(TicketStatus.values()).map(DynamoItems::counterFor).distinct())
                .hasSize(TicketStatus.values().length);
    }

    @Test
    void missingNumericAttributesDefaultToZero() {
        assertThat(DynamoItems.num(Map.of(), "x")).isZero();
        assertThat(DynamoItems.num(Map.of("x", AttributeValue.builder().nul(true).build()), "x")).isZero();
        assertThat(DynamoItems.millis(Map.of(), "x")).isNull();
        assertThat(DynamoItems.str(Map.of(), "x")).isNull();
    }

    @Test
    void schemaDeclaresFourTablesWithSparseIndexes() {
        List<CreateTableRequest> defs = DynamoSchema.tableDefinitions(DynamoTables.defaults());
        assertThat(defs).extracting(CreateTableRequest::tableName)
                .containsExactly("ticketing-events", "ticketing-tickets", "ticketing-orders", "ticketing-ticket-audit");
        assertThat(defs.get(1).globalSecondaryIndexes()).hasSize(1);
        assertThat(defs.get(2).globalSecondaryIndexes()).hasSize(1);
    }
}
