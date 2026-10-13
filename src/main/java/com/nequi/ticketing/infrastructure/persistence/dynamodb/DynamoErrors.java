package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import java.util.List;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

final class DynamoErrors {

    private static final String CONDITION_FAILED = "ConditionalCheckFailed";

    private DynamoErrors() {}

    /** A failed condition means somebody else changed the item first: an optimistic-locking conflict. */
    static <T> Mono<T> conflictOnFailedCondition(Mono<T> source, String message) {
        return source.onErrorMap(ConditionalCheckFailedException.class, e -> new ConcurrentUpdateException(message));
    }

    static boolean conditionFailed(TransactionCanceledException e, int itemIndex) {
        List<CancellationReason> reasons = e.cancellationReasons();
        return itemIndex >= 0 && itemIndex < reasons.size() && CONDITION_FAILED.equals(reasons.get(itemIndex).code());
    }

    /** True when any item in {@code [from, to)} failed its condition. */
    static boolean anyConditionFailed(TransactionCanceledException e, int from, int to) {
        for (int i = from; i < to; i++) {
            if (conditionFailed(e, i)) {
                return true;
            }
        }
        return false;
    }
}
