package com.paytm.seatreservation.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.seatreservation.model.Reservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ReservationDao {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReservationDao(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(Reservation reservation) {
        jdbc.update("""
                        INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                reservation.id().toString(),
                reservation.showId().toString(),
                reservation.userId(),
                toJson(reservation.seats()),
                reservation.amountPaise(),
                reservation.status().value());
    }

    private String toJson(List<String> seats) {
        try {
            return objectMapper.writeValueAsString(seats);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize seat list", e);
        }
    }
}
