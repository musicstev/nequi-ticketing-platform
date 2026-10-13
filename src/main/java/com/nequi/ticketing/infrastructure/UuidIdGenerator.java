package com.nequi.ticketing.infrastructure;

import com.nequi.ticketing.domain.port.IdGenerator;
import java.util.UUID;

public class UuidIdGenerator implements IdGenerator {

    @Override
    public String next() {
        return UUID.randomUUID().toString();
    }
}
