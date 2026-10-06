package com.paytm.seatreservation.controller;

import com.paytm.seatreservation.dto.CreateShowRequest;
import com.paytm.seatreservation.dto.SeatCountsResponse;
import com.paytm.seatreservation.dto.SeatResponse;
import com.paytm.seatreservation.dto.ShowResponse;
import com.paytm.seatreservation.exception.BadRequestException;
import com.paytm.seatreservation.exception.NotFoundException;
import com.paytm.seatreservation.model.SeatCounts;
import com.paytm.seatreservation.model.ShowState;
import com.paytm.seatreservation.service.ShowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
public class ShowController {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_SEATS = 10_000;
    private static final Pattern SEAT_LABEL = Pattern.compile("[A-Za-z0-9-]{1,16}");

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping("/shows")
    public ResponseEntity<ShowResponse> createShow(@RequestBody CreateShowRequest request) {
        validate(request);
        ShowState created = showService.createShow(
                request.name().trim(), request.seats(), request.pricePaise(), request.perUserLimit());
        return ResponseEntity
                .created(URI.create("/shows/" + created.show().id()))
                .body(toResponse(created));
    }

    @GetMapping("/shows/{id}")
    public ShowResponse getShow(@PathVariable String id) {
        return toResponse(showService.getShow(parseShowId(id)));
    }

    private void validate(CreateShowRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (request.name().length() > MAX_NAME_LENGTH) {
            throw new BadRequestException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        validateSeatLabels(request.seats());
        if (request.pricePaise() == null || request.pricePaise() < 0) {
            throw new BadRequestException("price_paise is required and must be a non-negative integer");
        }
        if (request.perUserLimit() != null && request.perUserLimit() < 1) {
            throw new BadRequestException("per_user_limit must be at least 1");
        }
    }

    private void validateSeatLabels(List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw new BadRequestException("seats must be a non-empty list");
        }
        if (seats.size() > MAX_SEATS) {
            throw new BadRequestException("a show can have at most " + MAX_SEATS + " seats");
        }
        Set<String> seen = new HashSet<>();
        for (String label : seats) {
            if (label == null || !SEAT_LABEL.matcher(label).matches()) {
                throw new BadRequestException("invalid seat label: " + label);
            }
            if (!seen.add(label)) {
                throw new BadRequestException("duplicate seat label: " + label);
            }
        }
    }

    /** A malformed id cannot match any show, so it is a 404 rather than a 400. */
    private UUID parseShowId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Show not found");
        }
    }

    private ShowResponse toResponse(ShowState state) {
        SeatCounts counts = state.counts();
        List<SeatResponse> seats = state.seats().stream()
                .map(seat -> new SeatResponse(seat.label(), seat.status().value()))
                .toList();
        return new ShowResponse(
                state.show().id(),
                state.show().name(),
                state.show().pricePaise(),
                state.show().perUserLimit(),
                state.show().totalSeats(),
                new SeatCountsResponse(counts.available(), counts.held(), counts.confirmed(), counts.total()),
                seats);
    }
}
