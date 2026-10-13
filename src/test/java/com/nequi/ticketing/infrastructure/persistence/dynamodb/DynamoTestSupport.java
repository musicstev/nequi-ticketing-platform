package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

final class DynamoTestSupport {

    private DynamoTestSupport() {}

    static <T> CompletableFuture<T> ok(T value) {
        return CompletableFuture.completedFuture(value);
    }

    static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    /** Builds a TransactionCanceledException whose per-item reason codes are given in order. */
    static TransactionCanceledException canceled(String... codes) {
        List<CancellationReason> reasons = Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList();
        return (TransactionCanceledException) TransactionCanceledException.builder()
                .message("canceled")
                .cancellationReasons(reasons)
                .build();
    }
}
