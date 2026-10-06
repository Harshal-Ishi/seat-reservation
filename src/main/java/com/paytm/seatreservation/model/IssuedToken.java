package com.paytm.seatreservation.model;

import com.paytm.seatreservation.security.Role;

public record IssuedToken(String token, String userId, Role role, long expiresInSeconds) {
}
