package com.paytm.seatreservation.dto;

/** request_id matches the X-Request-Id header and the log lines of the failed request. */
public record ErrorResponse(String error, String message, String requestId) {
}
