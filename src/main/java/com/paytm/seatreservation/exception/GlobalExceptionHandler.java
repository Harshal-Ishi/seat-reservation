package com.paytm.seatreservation.exception;

import com.paytm.seatreservation.dto.ErrorResponse;
import com.paytm.seatreservation.model.DeclineReason;
import com.paytm.seatreservation.observability.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e) {
        return error(HttpStatus.BAD_REQUEST, "bad-request", e.getMessage());
    }

    /** Malformed JSON, or a value of the wrong type such as a float for price_paise. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "bad-request", "Request body is not valid JSON or has a field of the wrong type");
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(errorBody("unauthorized", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, "forbidden", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.reason().value(), e.getMessage());
    }

    /**
     * The service is saturated, not broken: no DB connection within the pool timeout
     * (CannotCreateTransactionException / CannotGetJdbcConnectionException), or a row lock not granted within
     * innodb_lock_wait_timeout or lost to a deadlock (PessimisticLockingFailureException). The transaction has
     * already rolled back, so nothing changed and the client can safely retry. A 5xx here would be wrong.
     */
    @ExceptionHandler({
            CannotCreateTransactionException.class,
            CannotGetJdbcConnectionException.class,
            PessimisticLockingFailureException.class
    })
    public ResponseEntity<ErrorResponse> handleOverload(RuntimeException e) {
        log.warn("Declined as overloaded: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(errorBody(DeclineReason.OVERLOADED.value(), "Too many requests right now; retry shortly"));
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(errorBody(error, message));
    }

    private ErrorResponse errorBody(String error, String message) {
        return new ErrorResponse(error, message, MDC.get(RequestIdFilter.MDC_REQUEST_ID));
    }
}
