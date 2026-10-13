package com.nequi.ticketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UuidIdGeneratorTest {

    @Test
    void generatesUniqueUuidStrings() {
        UuidIdGenerator generator = new UuidIdGenerator();
        assertThat(generator.next()).matches("[0-9a-f-]{36}").isNotEqualTo(generator.next());
    }
}
