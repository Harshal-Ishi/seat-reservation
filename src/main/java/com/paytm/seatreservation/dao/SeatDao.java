package com.paytm.seatreservation.dao;

import com.paytm.seatreservation.model.Seat;
import com.paytm.seatreservation.model.SeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SeatDao {

    private final JdbcTemplate jdbc;

    public SeatDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts all seats as available, in one JDBC batch. */
    public void insertAvailable(UUID showId, List<String> labels) {
        List<Object[]> rows = new ArrayList<>(labels.size());
        for (int position = 0; position < labels.size(); position++) {
            rows.add(new Object[]{showId.toString(), labels.get(position), position, SeatStatus.AVAILABLE.value()});
        }
        jdbc.batchUpdate("""
                INSERT INTO seats (show_id, seat_label, position, status)
                VALUES (?, ?, ?, ?)
                """, rows);
    }

    /**
     * Locks one seat row until the transaction ends and returns its current status, or empty if the
     * show has no such seat. Callers lock seats one at a time in sorted order so that two requests
     * for overlapping seats always take their locks in the same order and cannot deadlock.
     */
    public Optional<SeatStatus> lockSeat(UUID showId, String seatLabel) {
        return jdbc.query("""
                                SELECT status
                                FROM seats
                                WHERE show_id = ? AND seat_label = ?
                                FOR UPDATE
                                """,
                        (rs, rowNum) -> SeatStatus.fromValue(rs.getString("status")),
                        showId.toString(), seatLabel)
                .stream()
                .findFirst();
    }

    /**
     * The atomic decision: confirms the seats only where they are still available, in one statement.
     * Returns how many rows changed; fewer than requested means at least one seat was already taken.
     */
    public int confirmIfAvailable(UUID showId, List<String> seatLabels, UUID reservationId) {
        // Only "?" placeholders are joined into the SQL; the labels themselves are bound as parameters.
        String placeholders = String.join(", ", Collections.nCopies(seatLabels.size(), "?"));
        List<Object> params = new ArrayList<>(seatLabels.size() + 3);
        params.add(SeatStatus.CONFIRMED.value());
        params.add(reservationId.toString());
        params.add(showId.toString());
        params.addAll(seatLabels);
        params.add(SeatStatus.AVAILABLE.value());
        return jdbc.update("""
                        UPDATE seats
                        SET status = ?, reservation_id = ?
                        WHERE show_id = ? AND seat_label IN (%s) AND status = ?
                        """.formatted(placeholders),
                params.toArray());
    }

    /**
     * Frees only the seats that belong to this reservation. A seat already re-booked by someone else carries their
     * reservation id, so it can never match here: a release can't resurrect another user's seat.
     */
    public int releaseByReservation(UUID reservationId) {
        return jdbc.update("""
                        UPDATE seats
                        SET status = ?, reservation_id = NULL
                        WHERE reservation_id = ?
                        """,
                SeatStatus.AVAILABLE.value(), reservationId.toString());
    }

    public int countAvailable(UUID showId) {
        return jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM seats
                        WHERE show_id = ? AND status = ?
                        """,
                Integer.class, showId.toString(), SeatStatus.AVAILABLE.value());
    }

    public List<Seat> findByShowId(UUID showId) {
        return jdbc.query("""
                        SELECT seat_label, status
                        FROM seats
                        WHERE show_id = ?
                        ORDER BY position
                        """,
                (rs, rowNum) -> new Seat(rs.getString("seat_label"), SeatStatus.fromValue(rs.getString("status"))),
                showId.toString());
    }
}
