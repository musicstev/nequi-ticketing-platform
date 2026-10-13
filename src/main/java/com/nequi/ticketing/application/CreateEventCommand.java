package com.nequi.ticketing.application;

import java.time.Instant;

public record CreateEventCommand(String name, Instant date, String venue, int capacity) {}
