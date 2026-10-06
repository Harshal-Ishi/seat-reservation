package com.paytm.seatreservation.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.seatreservation.model.Reservation;
import com.paytm.seatreservation.model.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationDao {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JavaType seatListType;
    private final RowMapper<Reservation> rowMapper;

    public ReservationDao(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.seatListType = objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);
        this.rowMapper = (rs, rowNum) -> new Reservation(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("show_id")),
                rs.getString("user_id"),
                fromJson(rs.getString("seats")),
                rs.getLong("amount_paise"),
                ReservationStatus.fromValue(rs.getString("status")),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"));
    }

    /**
     * Inserts the reservation. Throws DuplicateKeyException if this user already used the idempotency key.
     * If another transaction holds an uncommitted row with the same key, InnoDB makes this insert wait until that
     * transaction commits (then it fails as a duplicate) or rolls back (then it succeeds).
     */
    public void insert(Reservation reservation) {
        jdbc.update("""
                        INSERT INTO reservations
                            (id, show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reservation.id().toString(),
                reservation.showId().toString(),
                reservation.userId(),
                toJson(reservation.seats()),
                reservation.amountPaise(),
                reservation.status().value(),
                reservation.idempotencyKey(),
                reservation.requestHash());
    }

    public Optional<Reservation> findByUserAndIdempotencyKey(String userId, String idempotencyKey) {
        return jdbc.query("""
                                SELECT id, show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash
                                FROM reservations
                                WHERE user_id = ? AND idempotency_key = ?
                                """,
                        rowMapper, userId, idempotencyKey)
                .stream()
                .findFirst();
    }

    private String toJson(List<String> seats) {
        try {
            return objectMapper.writeValueAsString(seats);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize seat list", e);
        }
    }

    private List<String> fromJson(String seats) {
        try {
            return objectMapper.readValue(seats, seatListType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read seat list: " + seats, e);
        }
    }
}
