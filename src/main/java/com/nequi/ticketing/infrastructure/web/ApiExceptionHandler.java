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
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/** Maps every failure to an RFC 9457 problem document. Unexpected errors never leak internals. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException e) {
        // Exhaustive over the sealed hierarchy: adding a new exception without mapping it will not compile.
        HttpStatus status = switch (e) {
            case EventNotFoundException _, OrderNotFoundException _ -> HttpStatus.NOT_FOUND;
            case InsufficientInventoryException _,
                    InvalidStateTransitionException _,
                    ConcurrentUpdateException _ -> HttpStatus.CONFLICT;
            case ReservationExpiredException _ -> HttpStatus.GONE;
            case InvalidRequestException _ -> HttpStatus.BAD_REQUEST;
            case IdempotencyConflictException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case QueueUnavailableException _ -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        if (status.is5xxServerError()) {
            LOG.error("Service unavailable: {}", e.toString());
        }
        return problem(status, status.getReasonPhrase(), e.getMessage(), null);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ProblemDetail> handleValidation(WebExchangeBindException e) {
        // Field name + message only: rejected values are never echoed back.
        List<Map<String, String>> errors = e.getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request validation failed", errors);
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(ServerWebInputException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request", "Request body or parameters could not be read", null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> handleStatus(ResponseStatusException e) {
        HttpStatusCode code = e.getStatusCode();
        HttpStatus status = HttpStatus.resolve(code.value());
        String title = status == null ? "Error" : status.getReasonPhrase();
        return problem(code, title, e.getReason() == null ? title : e.getReason(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        LOG.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "Unexpected error", null);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String title, String detail, Object errors) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        if (errors != null) {
            body.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
