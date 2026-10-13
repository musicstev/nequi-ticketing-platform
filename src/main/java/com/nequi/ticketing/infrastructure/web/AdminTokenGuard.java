package com.nequi.ticketing.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Protects privileged operations with a shared secret that is injected from the environment. */
public class AdminTokenGuard {

    private final byte[] expected;

    public AdminTokenGuard(String expectedToken) {
        this.expected = expectedToken == null || expectedToken.isBlank()
                ? null
                : expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public void require(String provided) {
        boolean valid = expected != null
                && provided != null
                && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }
}
