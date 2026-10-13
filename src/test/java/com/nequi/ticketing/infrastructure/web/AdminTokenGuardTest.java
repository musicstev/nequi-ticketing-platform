package com.nequi.ticketing.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminTokenGuardTest {

    @Test
    void acceptsOnlyTheConfiguredToken() {
        AdminTokenGuard guard = new AdminTokenGuard("s3cret");
        assertThatCode(() -> guard.require("s3cret")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.require("other")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> guard.require(null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anUnconfiguredTokenDisablesTheEndpointEntirely() {
        for (String configured : new String[] {null, "", "  "}) {
            AdminTokenGuard guard = new AdminTokenGuard(configured);
            assertThatThrownBy(() -> guard.require(configured)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> guard.require("anything")).isInstanceOf(ResponseStatusException.class);
        }
    }
}
