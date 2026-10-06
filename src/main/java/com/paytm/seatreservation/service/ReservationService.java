package com.paytm.seatreservation.service;

import com.paytm.seatreservation.dao.ReservationDao;
import com.paytm.seatreservation.dao.SeatDao;
import com.paytm.seatreservation.dao.ShowDao;
import com.paytm.seatreservation.exception.BadRequestException;
import com.paytm.seatreservation.exception.ConflictException;
import com.paytm.seatreservation.exception.NotFoundException;
import com.paytm.seatreservation.model.DeclineReason;
import com.paytm.seatreservation.model.Reservation;
import com.paytm.seatreservation.model.ReservationStatus;
import com.paytm.seatreservation.model.ReserveResult;
import com.paytm.seatreservation.model.Show;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

    private final ShowDao showDao;
    private final SeatDao seatDao;
    private final ReservationDao reservationDao;
    private final TransactionRunner transactionRunner;

    public ReservationService(ShowDao showDao,
                              SeatDao seatDao,
                              ReservationDao reservationDao,
                              TransactionRunner transactionRunner) {
        this.showDao = showDao;
        this.seatDao = seatDao;
        this.reservationDao = reservationDao;
        this.transactionRunner = transactionRunner;
    }

    /**
     * Reserves all requested seats for the user, or none of them (all-or-nothing), at most once per idempotency key.
     * The seat labels must already be distinct and well-formed.
     */
    public ReserveResult reserve(UUID showId, String userId, List<String> seatLabels, String idempotencyKey) {
        Show show = showDao.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show not found"));

        // Sorted once and used for locking, hashing and the response. Java's String order matches the
        // ascii_bin collation of seat_label, so every transaction locks overlapping seats in the same order.
        List<String> sortedLabels = seatLabels.stream().sorted().toList();
        String requestHash = requestHash(showId, sortedLabels);
        Reservation reservation = new Reservation(
                UUID.randomUUID(),
                showId,
                userId,
                sortedLabels,
                Math.multiplyExact(show.pricePaise(), sortedLabels.size()),
                ReservationStatus.CONFIRMED,
                idempotencyKey,
                requestHash);

        try {
            // Any exception thrown inside rolls the whole transaction back: no reservation row, no seat changed.
            transactionRunner.runWithDeadlockRetry(() -> {
                // Step 1: claim the key. The unique (user_id, idempotency_key) index decides, atomically.
                reservationDao.insert(reservation);

                // Step 2: lock the seats in sorted order.
                for (String label : sortedLabels) {
                    if (seatDao.lockSeat(showId, label).isEmpty()) {
                        throw new BadRequestException("Unknown seat: " + label);
                    }
                }

                // Step 3: the atomic decision.
                int confirmed = seatDao.confirmIfAvailable(showId, sortedLabels, reservation.id());
                if (confirmed != sortedLabels.size()) {
                    throw new ConflictException(DeclineReason.SEAT_TAKEN, "One or more requested seats are not available");
                }
            });
        } catch (DuplicateKeyException e) {
            return replayExisting(userId, idempotencyKey, requestHash);
        }
        return new ReserveResult(reservation, false);
    }

    /**
     * The key was already used by a committed reservation (the duplicate-key error only fires once that transaction
     * committed). Same request → hand back the original; anything else on the same key → 409.
     */
    private ReserveResult replayExisting(String userId, String idempotencyKey, String requestHash) {
        Reservation existing = reservationDao.findByUserAndIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Duplicate idempotency key but no reservation found"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new ConflictException(DeclineReason.IDEMPOTENCY_KEY_REUSED,
                    "This idempotency key was already used for a different request");
        }
        return new ReserveResult(existing, true);
    }

    /** Identifies "the same request": same show and same set of seats, in any order. */
    private String requestHash(UUID showId, List<String> sortedLabels) {
        String canonical = showId + "|" + String.join(",", sortedLabels);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
