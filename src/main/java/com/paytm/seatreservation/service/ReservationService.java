package com.paytm.seatreservation.service;

import com.paytm.seatreservation.dao.ReservationDao;
import com.paytm.seatreservation.dao.SeatDao;
import com.paytm.seatreservation.dao.ShowDao;
import com.paytm.seatreservation.dao.UserSeatCountDao;
import com.paytm.seatreservation.exception.BadRequestException;
import com.paytm.seatreservation.exception.ConflictException;
import com.paytm.seatreservation.exception.ForbiddenException;
import com.paytm.seatreservation.exception.NotFoundException;
import com.paytm.seatreservation.model.CancelResult;
import com.paytm.seatreservation.model.DeclineReason;
import com.paytm.seatreservation.model.Reservation;
import com.paytm.seatreservation.model.ReservationStatus;
import com.paytm.seatreservation.model.ReservePrecheck;
import com.paytm.seatreservation.model.ReserveResult;
import com.paytm.seatreservation.model.Show;
import com.paytm.seatreservation.observability.ReservationMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReservationService {

    private final ShowDao showDao;
    private final SeatDao seatDao;
    private final ReservationDao reservationDao;
    private final UserSeatCountDao userSeatCountDao;
    private final TransactionRunner transactionRunner;
    private final ReservationMetrics metrics;

    public ReservationService(ShowDao showDao,
                              SeatDao seatDao,
                              ReservationDao reservationDao,
                              UserSeatCountDao userSeatCountDao,
                              TransactionRunner transactionRunner,
                              ReservationMetrics metrics) {
        this.showDao = showDao;
        this.seatDao = seatDao;
        this.reservationDao = reservationDao;
        this.userSeatCountDao = userSeatCountDao;
        this.transactionRunner = transactionRunner;
        this.metrics = metrics;
    }

    /**
     * Reserves all requested seats for the user, or none of them (all-or-nothing), at most once per idempotency key.
     * The seat labels must already be distinct and well-formed. Every outcome is recorded once the transaction is over.
     */
    public ReserveResult reserve(UUID showId, String userId, List<String> seatLabels, String idempotencyKey) {
        long startNanos = System.nanoTime();
        int seatCount = seatLabels.size();
        try {
            ReserveResult result = attemptReserve(showId, userId, seatLabels, idempotencyKey);
            UUID reservationId = result.reservation().id();
            if (result.replayed()) {
                metrics.declined(DeclineReason.IDEMPOTENT_REPLAY, showId, reservationId, seatCount, elapsedMillis(startNanos));
            } else {
                metrics.confirmed(showId, reservationId, seatCount, elapsedMillis(startNanos));
            }
            return result;
        } catch (ConflictException e) {
            metrics.declined(e.reason(), showId, null, seatCount, elapsedMillis(startNanos));
            throw e;
        } catch (CannotCreateTransactionException | CannotGetJdbcConnectionException | PessimisticLockingFailureException e) {
            // Mapped to 429 by GlobalExceptionHandler; counted here so the metric knows it was a reserve.
            metrics.declined(DeclineReason.OVERLOADED, showId, null, seatCount, elapsedMillis(startNanos));
            throw e;
        }
    }

    private ReserveResult attemptReserve(UUID showId, String userId, List<String> seatLabels, String idempotencyKey) {
        // Sorted once and used for locking, hashing and the response. Java's String order matches the
        // ascii_bin collation of seat_label, so every transaction locks overlapping seats in the same order.
        List<String> sortedLabels = seatLabels.stream().sorted().toList();
        ReservePrecheck precheck = showDao.findWithTakenSeats(showId, sortedLabels)
                .orElseThrow(() -> new NotFoundException("Show not found"));
        Show show = precheck.show();
        // Seats are never deleted, so a label missing now is missing for good: 400 before any transaction.
        if (precheck.existingSeats() != sortedLabels.size()) {
            throw new BadRequestException("One or more requested seats do not exist in this show");
        }
        // A request bigger than the limit can never succeed. Checked here because the counter row's very first
        // insert (seat_count 0) has no WHERE to stop it.
        if (seatLabels.size() > show.perUserLimit()) {
            throw perUserLimitExceeded(show);
        }

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

        // Fast path: the plain read above (no transaction, no locks). It only ever turns a request away early; it never
        // reserves anything (read-then-reject, not read-then-write). Without it, the hundreds of losers of a hot
        // seat would each hold a DB connection while queueing on that seat's row lock, and time out into 429s.
        // A stale read here can only decline a seat that was released a moment ago, never sell one twice: the
        // locked transaction below is still the only place a seat is confirmed.
        if (precheck.takenSeats() > 0) {
            // Seats taken, but maybe by this very request's earlier attempt: a retry must get its reservation back,
            // not a 409. Seat and reservation commit in one transaction, so if the seat read above saw our seat
            // taken, this later read is guaranteed to see our reservation. (Reading the key first would race.)
            Optional<Reservation> existing = reservationDao.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return replayOrReject(existing.get(), requestHash);
            }
            throw new ConflictException(DeclineReason.SEAT_TAKEN, "One or more requested seats are not available");
        }

        try {
            // Any exception thrown inside rolls the whole transaction back: no reservation row, no seat changed.
            transactionRunner.runWithDeadlockRetry(() -> {
                // Step 1: claim the key. The unique (user_id, idempotency_key) index decides, atomically.
                reservationDao.insert(reservation);

                // Step 2: the per-user limit. The guarded UPDATE runs on this user's locked counter row, so parallel
                // requests from one user are decided one at a time, each seeing the previous ones' committed total.
                userSeatCountDao.ensureRow(showId, userId);
                if (userSeatCountDao.addIfWithinLimit(showId, userId, sortedLabels.size(), show.perUserLimit()) == 0) {
                    throw perUserLimitExceeded(show);
                }

                // Step 3 (multi-seat only): lock the seats in sorted order, so two requests for overlapping seats
                // take their locks in the same order and can't deadlock. A single seat has no ordering to get wrong,
                // so it goes straight to step 4. That matters for a hot seat: under READ COMMITTED, an UPDATE whose
                // WHERE no longer matches (seat already confirmed) neither waits for nor takes the row lock, so losers
                // fail in one round trip instead of queueing on the lock behind each other.
                if (sortedLabels.size() > 1) {
                    for (String label : sortedLabels) {
                        seatDao.lockSeat(showId, label);
                    }
                }

                // Step 4: the atomic decision.
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
     * Cancels the caller's reservation and returns its seats to the pool. Only the owner may cancel.
     * Cancelling an already-cancelled reservation is a no-op that returns it again, so a retried cancel is safe.
     * Locks are taken in the same order as reserve (reservation → counter → sorted seats), so the two can't deadlock.
     */
    public Reservation cancel(UUID reservationId, String userId) {
        CancelResult result = attemptCancel(reservationId, userId);
        Reservation reservation = result.reservation();
        if (result.changed()) {
            metrics.cancelled(reservation.showId(), reservation.id(), reservation.seats().size());
        }
        return reservation;
    }

    private CancelResult attemptCancel(UUID reservationId, String userId) {
        return transactionRunner.runWithDeadlockRetry(() -> {
            // Locking the row first means the owner and status checks below can't go stale before we write.
            Reservation reservation = reservationDao.findByIdForUpdate(reservationId)
                    .orElseThrow(() -> new NotFoundException("Reservation not found"));
            if (!reservation.userId().equals(userId)) {
                throw new ForbiddenException("Only the owner can cancel this reservation");
            }
            if (reservation.status() == ReservationStatus.CANCELLED) {
                return new CancelResult(reservation, false);
            }

            int seatCount = reservation.seats().size();
            reservationDao.markCancelled(reservationId);
            if (userSeatCountDao.subtract(reservation.showId(), userId, seatCount) != 1) {
                throw new IllegalStateException("Seat counter for user " + userId + " is lower than a confirmed reservation");
            }
            // Stored seats are already sorted, so this matches the lock order used by reserve.
            for (String label : reservation.seats()) {
                seatDao.lockSeat(reservation.showId(), label);
            }
            int released = seatDao.releaseByReservation(reservationId);
            if (released != seatCount) {
                throw new IllegalStateException("Reservation " + reservationId + " held " + released + " seats, expected " + seatCount);
            }
            return new CancelResult(withStatus(reservation, ReservationStatus.CANCELLED), true);
        });
    }

    private Reservation withStatus(Reservation reservation, ReservationStatus status) {
        return new Reservation(reservation.id(), reservation.showId(), reservation.userId(), reservation.seats(),
                reservation.amountPaise(), status, reservation.idempotencyKey(), reservation.requestHash());
    }

    /**
     * The key was already used by a committed reservation (the duplicate-key error only fires once that transaction
     * committed). Same request → hand back the original; anything else on the same key → 409.
     */
    private ReserveResult replayExisting(String userId, String idempotencyKey, String requestHash) {
        Reservation existing = reservationDao.findByUserAndIdempotencyKey(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Duplicate idempotency key but no reservation found"));
        return replayOrReject(existing, requestHash);
    }

    /** Same request on a used key → the original reservation (200); a different request → 409. */
    private ReserveResult replayOrReject(Reservation existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ConflictException(DeclineReason.IDEMPOTENCY_KEY_REUSED,
                    "This idempotency key was already used for a different request");
        }
        return new ReserveResult(existing, true);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private ConflictException perUserLimitExceeded(Show show) {
        return new ConflictException(DeclineReason.PER_USER_LIMIT,
                "A user can hold at most " + show.perUserLimit() + " seats for this show");
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
