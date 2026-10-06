package com.paytm.seatreservation.dao;

import com.paytm.seatreservation.model.Show;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
