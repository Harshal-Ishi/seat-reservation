package com.paytm.seatreservation.dao;

import com.paytm.seatreservation.model.Seat;
import com.paytm.seatreservation.model.SeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
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
