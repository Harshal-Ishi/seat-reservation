package com.paytm.seatreservation.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class UserSeatCountDao {

    private final JdbcTemplate jdbc;

    public UserSeatCountDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes sure the user's counter row exists (starting at 0), and locks it either way.
     * MySQL's ON DUPLICATE KEY UPDATE has no WHERE clause, so the limit check is a separate guarded UPDATE
     * ({@link #addIfWithinLimit}); "seat_count = seat_count" makes this statement a no-op for an existing row.
     */
    public void ensureRow(UUID showId, String userId) {
        jdbc.update("""
                        INSERT INTO user_seat_counts (show_id, user_id, seat_count)
                        VALUES (?, ?, 0)
                        ON DUPLICATE KEY UPDATE seat_count = seat_count
                        """,
                showId.toString(), userId);
    }

    /**
     * Adds the seats only if the total stays within the limit, in one statement on the locked row.
     * Returns 1 if added, 0 if it would exceed the limit.
     */
    public int addIfWithinLimit(UUID showId, String userId, int seats, int limit) {
        return jdbc.update("""
                        UPDATE user_seat_counts
                        SET seat_count = seat_count + ?
                        WHERE show_id = ? AND user_id = ? AND seat_count + ? <= ?
                        """,
                seats, showId.toString(), userId, seats, limit);
    }
}
