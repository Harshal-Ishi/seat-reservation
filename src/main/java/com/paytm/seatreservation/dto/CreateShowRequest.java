package com.paytm.seatreservation.dto;

import java.util.List;

/** Wrapper types so a missing field is null and can be rejected, rather than silently 0. */
public record CreateShowRequest(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {
}
