package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.domain.exception.InvalidRequestException;
import java.util.regex.Pattern;

final class Ids {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private Ids() {}

    static String require(String id) {
        if (id == null || !VALID.matcher(id).matches()) {
            throw new InvalidRequestException("Malformed identifier");
        }
        return id;
    }
}
