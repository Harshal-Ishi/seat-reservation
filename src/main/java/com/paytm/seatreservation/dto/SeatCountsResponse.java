package com.paytm.seatreservation.dto;

public record SeatCountsResponse(int available, int held, int confirmed, int total) {
}
