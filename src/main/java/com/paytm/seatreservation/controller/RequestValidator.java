package com.paytm.seatreservation.controller;

import com.paytm.seatreservation.exception.BadRequestException;
import com.paytm.seatreservation.exception.NotFoundException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Request-shape checks shared by controllers. No business rules here. */
public final class RequestValidator {

    private static final Pattern SEAT_LABEL = Pattern.compile("[A-Za-z0-9-]{1,16}");

    private RequestValidator() {
    }

    /** Non-empty, at most maxSeats, every label well-formed, no duplicates. */
    public static void validateSeatLabels(List<String> seats, int maxSeats) {
        if (seats == null || seats.isEmpty()) {
            throw new BadRequestException("seats must be a non-empty list");
        }
        if (seats.size() > maxSeats) {
            throw new BadRequestException("at most " + maxSeats + " seats are allowed");
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

    /** A malformed id cannot match anything, so it is a 404 rather than a 400. */
    public static UUID parseIdOrNotFound(String id, String notFoundMessage) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(notFoundMessage);
        }
    }
}
