package com.paytm.seatreservation.controller;

import com.paytm.seatreservation.dto.ReservationResponse;
import com.paytm.seatreservation.dto.ReserveRequest;
import com.paytm.seatreservation.exception.BadRequestException;
import com.paytm.seatreservation.model.Reservation;
import com.paytm.seatreservation.model.ReserveResult;
import com.paytm.seatreservation.security.AuthenticatedUser;
import com.paytm.seatreservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReservationController {

    // A request can never be larger than a show.
    private static final int MAX_SEATS_PER_REQUEST = 10_000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** 201 for a new reservation; 200 with the original reservation when the idempotency key is replayed. */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(AuthenticatedUser caller,
                                                       @PathVariable String showId,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                       @RequestBody ReserveRequest request) {
        UUID id = RequestValidator.parseIdOrNotFound(showId, "Show not found");
        RequestValidator.validateSeatLabels(request.seats(), MAX_SEATS_PER_REQUEST);
        String idempotencyKey = resolveIdempotencyKey(request.idempotencyKey(), headerKey);

        ReserveResult result = reservationService.reserve(id, caller.userId(), request.seats(), idempotencyKey);
        return ResponseEntity
                .status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toResponse(result.reservation()));
    }

    /** Owner only. 200 with the cancelled reservation, also when it was already cancelled. */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(AuthenticatedUser caller, @PathVariable String reservationId) {
        UUID id = RequestValidator.parseIdOrNotFound(reservationId, "Reservation not found");
        return toResponse(reservationService.cancel(id, caller.userId()));
    }

    /** Body field or Idempotency-Key header; if both are sent they must agree. */
    private String resolveIdempotencyKey(String bodyKey, String headerKey) {
        if (bodyKey != null && headerKey != null && !bodyKey.equals(headerKey)) {
            throw new BadRequestException("idempotency_key in the body and the Idempotency-Key header differ");
        }
        String key = bodyKey != null ? bodyKey : headerKey;
        if (key == null || key.isBlank()) {
            throw new BadRequestException("idempotency_key is required (body field or Idempotency-Key header)");
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("idempotency_key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return key;
    }

    private ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.id(),
                reservation.showId(),
                reservation.userId(),
                reservation.seats(),
                reservation.amountPaise(),
                reservation.status().value());
    }
}
