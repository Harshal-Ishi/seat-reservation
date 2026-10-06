package com.paytm.seatreservation.exception;

import com.paytm.seatreservation.model.DeclineReason;

/** A clean domain decline (409), e.g. the seat is already taken. Never a server error. */
public class ConflictException extends RuntimeException {

    private final DeclineReason reason;

    public ConflictException(DeclineReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DeclineReason reason() {
        return reason;
    }
}
