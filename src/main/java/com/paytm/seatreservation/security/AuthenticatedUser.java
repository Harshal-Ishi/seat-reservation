package com.paytm.seatreservation.security;

/**
 * The caller, as proven by a verified JWT. This is the only source of a user id;
 * request bodies are never trusted for identity.
 */
public record AuthenticatedUser(String userId, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
