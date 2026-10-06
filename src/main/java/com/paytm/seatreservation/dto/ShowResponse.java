package com.paytm.seatreservation.dto;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        SeatCountsResponse counts,
        List<SeatResponse> seats) {
}
