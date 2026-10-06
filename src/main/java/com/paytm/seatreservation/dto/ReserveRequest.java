package com.paytm.seatreservation.dto;

import java.util.List;

/**
 * No user id field: identity comes only from the bearer token. A user_id sent in the body is ignored.
 * The idempotency key may also come in the Idempotency-Key header instead of the body.
 */
public record ReserveRequest(List<String> seats, String idempotencyKey) {
}
