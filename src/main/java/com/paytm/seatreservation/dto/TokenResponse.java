package com.paytm.seatreservation.dto;

public record TokenResponse(String token, String userId, String role, long expiresIn) {
}
