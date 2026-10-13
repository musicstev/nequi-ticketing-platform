package com.nequi.ticketing.application;

import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.Ticket;
import java.util.List;

/** An order together with the current state of its tickets. */
public record OrderView(Order order, List<Ticket> tickets) {}
