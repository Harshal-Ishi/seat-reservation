package com.paytm.seatreservation.controller;

import com.paytm.seatreservation.dto.ReservationResponse;
import com.paytm.seatreservation.dto.ReserveRequest;
import com.paytm.seatreservation.model.Reservation;
import com.paytm.seatreservation.security.AuthenticatedUser;
import com.paytm.seatreservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ReservationController {

    // A request can never be larger than a show.
    private static final int MAX_SEATS_PER_REQUEST = 10_000;

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{showId}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(AuthenticatedUser caller,
                                       @PathVariable String showId,
                                       @RequestBody ReserveRequest request) {
        UUID id = RequestValidator.parseIdOrNotFound(showId, "Show not found");
        RequestValidator.validateSeatLabels(request.seats(), MAX_SEATS_PER_REQUEST);
        return toResponse(reservationService.reserve(id, caller.userId(), request.seats()));
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
