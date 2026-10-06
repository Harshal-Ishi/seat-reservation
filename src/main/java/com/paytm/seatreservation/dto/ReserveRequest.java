package com.paytm.seatreservation.dto;

import java.util.List;

/** No user id field: identity comes only from the bearer token. A user_id sent in the body is ignored. */
public record ReserveRequest(List<String> seats) {
}
