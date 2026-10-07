package com.paytm.seatreservation.dao;

import com.paytm.seatreservation.model.ReservePrecheck;
import com.paytm.seatreservation.model.SeatStatus;
import com.paytm.seatreservation.model.Show;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ShowDao {

    private final JdbcTemplate jdbc;

    public ShowDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Show show) {
        jdbc.update("""
                        INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                show.id().toString(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats());
    }

    public List<UUID> findAllIds() {
        return jdbc.query("SELECT id FROM shows", (rs, rowNum) -> UUID.fromString(rs.getString("id")));
    }

    /**
     * The show and, in the same round trip, how many of the requested seats are already taken. Plain read, no lock.
     * One statement instead of two matters when the database is a network hop away: under a burst, every round trip
     * a request makes is time it holds a pooled connection that other requests are queueing for.
     */
    public Optional<ReservePrecheck> findWithTakenSeats(UUID id, List<String> seatLabels) {
        // Only "?" placeholders are joined into the SQL; the labels themselves are bound as parameters.
        String placeholders = String.join(", ", Collections.nCopies(seatLabels.size(), "?"));
        List<Object> params = new ArrayList<>(seatLabels.size() + 3);
        params.add(id.toString());
        params.addAll(seatLabels);
        params.add(SeatStatus.AVAILABLE.value());
        params.add(id.toString());
        return jdbc.query("""
                                SELECT id, name, price_paise, per_user_limit, total_seats,
                                       (SELECT COUNT(*) FROM seats
                                        WHERE show_id = ? AND seat_label IN (%s) AND status <> ?) AS taken_seats
                                FROM shows
                                WHERE id = ?
                                """.formatted(placeholders),
                        (rs, rowNum) -> new ReservePrecheck(
                                new Show(
                                        UUID.fromString(rs.getString("id")),
                                        rs.getString("name"),
                                        rs.getLong("price_paise"),
                                        rs.getInt("per_user_limit"),
                                        rs.getInt("total_seats")),
                                rs.getInt("taken_seats")),
                        params.toArray())
                .stream()
                .findFirst();
    }

    public Optional<Show> findById(UUID id) {
        return jdbc.query("""
                                SELECT id, name, price_paise, per_user_limit, total_seats
                                FROM shows
                                WHERE id = ?
                                """,
                        (rs, rowNum) -> new Show(
                                UUID.fromString(rs.getString("id")),
                                rs.getString("name"),
                                rs.getLong("price_paise"),
                                rs.getInt("per_user_limit"),
                                rs.getInt("total_seats")),
                        id.toString())
                .stream()
                .findFirst();
    }
}
