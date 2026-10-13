package com.nequi.ticketing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TicketStatusTest {

    private static final Set<String> ALLOWED = Set.of(
            "AVAILABLE->RESERVED",
            "AVAILABLE->COMPLIMENTARY",
            "RESERVED->PENDING_CONFIRMATION",
            "RESERVED->AVAILABLE",
            "PENDING_CONFIRMATION->SOLD",
            "PENDING_CONFIRMATION->AVAILABLE");

    static Stream<Arguments> allPairs() {
        return Stream.of(TicketStatus.values())
                .flatMap(from -> Stream.of(TicketStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest
    @MethodSource("allPairs")
    void onlyDeclaredTransitionsAreAllowed(TicketStatus from, TicketStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(from + "->" + to));
    }

    @ParameterizedTest
    @MethodSource("allPairs")
    void requireTransitionThrowsExactlyWhenNotAllowed(TicketStatus from, TicketStatus to) {
        if (from.canTransitionTo(to)) {
            assertThatCode(() -> from.requireTransitionTo(to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> from.requireTransitionTo(to))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }
    }

    @Test
    void soldAndComplimentaryAreFinal() {
        assertThat(TicketStatus.SOLD.isFinal()).isTrue();
        assertThat(TicketStatus.COMPLIMENTARY.isFinal()).isTrue();
        assertThat(TicketStatus.AVAILABLE.isFinal()).isFalse();
        assertThat(TicketStatus.RESERVED.isFinal()).isFalse();
        assertThat(TicketStatus.PENDING_CONFIRMATION.isFinal()).isFalse();
    }

    @Test
    void onlySoldIsAccountableAndHoldsAreNotSales() {
        assertThat(Stream.of(TicketStatus.values()).filter(TicketStatus::isSale)).containsExactly(TicketStatus.SOLD);
        assertThat(TicketStatus.COMPLIMENTARY.isSale()).isFalse();
        assertThat(Stream.of(TicketStatus.values()).filter(TicketStatus::isHold))
                .containsExactly(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION);
    }
}
