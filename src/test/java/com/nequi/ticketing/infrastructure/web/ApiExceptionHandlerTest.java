package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.domain.exception.ConcurrentUpdateException;
import com.nequi.ticketing.domain.exception.DomainException;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.IdempotencyConflictException;
import com.nequi.ticketing.domain.exception.InsufficientInventoryException;
import com.nequi.ticketing.domain.exception.InvalidRequestException;
import com.nequi.ticketing.domain.exception.InvalidStateTransitionException;
import com.nequi.ticketing.domain.exception.OrderNotFoundException;
import com.nequi.ticketing.domain.exception.QueueUnavailableException;
import com.nequi.ticketing.domain.exception.ReservationExpiredException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class ApiExceptionHandlerTest {

    static class Boom {
        static DomainException current;
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/domain")
        Mono<String> domain() {
            return Mono.error(Boom.current);
        }

        @GetMapping("/unexpected")
        Mono<String> unexpected() {
            return Mono.error(new IllegalStateException("secret internal detail: jdbc://password"));
        }

        @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        Mono<String> json(@RequestBody String body) {
            return Mono.just(body);
        }
    }

    private final WebTestClient client = WebTestClient.bindToController(new ThrowingController())
            .controllerAdvice(new ApiExceptionHandler())
            .build();

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of(new EventNotFoundException("e"), 404),
                Arguments.of(new OrderNotFoundException("o"), 404),
                Arguments.of(new InsufficientInventoryException("e", 2), 409),
                Arguments.of(new InvalidStateTransitionException("order", "A", "B"), 409),
                Arguments.of(new ConcurrentUpdateException("conflict"), 409),
                Arguments.of(new IdempotencyConflictException("k"), 422),
                Arguments.of(new ReservationExpiredException("o"), 410),
                Arguments.of(new InvalidRequestException("bad"), 400),
                Arguments.of(new QueueUnavailableException("down", null), 503));
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void everyDomainErrorMapsToAProblemDocumentWithTheRightStatus(DomainException error, int status) {
        Boom.current = error;
        client.get().uri("/domain").exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(status)
                .jsonPath("$.detail").isNotEmpty();
    }

    @Test
    void unexpectedErrorsNeverLeakInternalDetails() {
        client.get().uri("/unexpected").exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Unexpected error")
                .jsonPath("$.title").isEqualTo("Internal Server Error");
    }

    @Test
    void unsupportedMediaTypesKeepTheirHttpStatus() {
        client.post().uri("/json").contentType(MediaType.TEXT_PLAIN).bodyValue("x").exchange()
                .expectStatus().isEqualTo(415)
                .expectBody().jsonPath("$.status").isEqualTo(415);
    }
}
