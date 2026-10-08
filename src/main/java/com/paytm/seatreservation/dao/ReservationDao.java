package com.paytm.seatreservation.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.seatreservation.model.Reservation;
import com.paytm.seatreservation.model.ReservationStatus;
import com.paytm.seatreservation.model.ReservePrecheck;
import com.paytm.seatreservation.model.SeatStatus;
import com.paytm.seatreservation.model.Show;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
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
        this.rowMapper = (rs, rowNum) -> mapReservation(rs, "");
    }

    /**
     * Everything a reserve needs to know before it opens a transaction, in one round trip: the show, how many of the
     * requested seats exist and are taken, and this user's reservation for this idempotency key, if any.
     * Plain read, no locks. Under READ COMMITTED one statement reads one consistent snapshot; a seat and its
     * reservation commit together, so if this read shows our own earlier reservation's seat as taken, it also shows
     * that reservation (two separate reads could miss it). One statement instead of three matters when the database
     * is a network hop away: every round trip is time a pooled connection is held while others queue for it.
     */
    public Optional<ReservePrecheck> precheck(UUID showId, List<String> seatLabels, String userId, String idempotencyKey) {
        // Only "?" placeholders are joined into the SQL; the labels themselves are bound as parameters.
        String placeholders = String.join(", ", Collections.nCopies(seatLabels.size(), "?"));
        List<Object> params = new ArrayList<>(seatLabels.size() + 5);
        params.add(SeatStatus.AVAILABLE.value());
        params.add(showId.toString());
        params.addAll(seatLabels);
        params.add(userId);
        params.add(idempotencyKey);
        params.add(showId.toString());
        return jdbc.query("""
                                SELECT sh.id AS show_id, sh.name, sh.price_paise, sh.per_user_limit, sh.total_seats,
                                       counts.existing_seats, counts.taken_seats,
                                       r.id AS r_id, r.show_id AS r_show_id, r.user_id AS r_user_id, r.seats AS r_seats,
                                       r.amount_paise AS r_amount_paise, r.status AS r_status,
                                       r.idempotency_key AS r_idempotency_key, r.request_hash AS r_request_hash
                                FROM shows sh
                                CROSS JOIN (
                                    SELECT COUNT(*) AS existing_seats, COALESCE(SUM(status <> ?), 0) AS taken_seats
                                    FROM seats
                                    WHERE show_id = ? AND seat_label IN (%s)
                                ) counts
                                LEFT JOIN reservations r ON r.user_id = ? AND r.idempotency_key = ?
                                WHERE sh.id = ?
                                """.formatted(placeholders),
                        (rs, rowNum) -> new ReservePrecheck(
                                new Show(
                                        UUID.fromString(rs.getString("show_id")),
                                        rs.getString("name"),
                                        rs.getLong("price_paise"),
                                        rs.getInt("per_user_limit"),
                                        rs.getInt("total_seats")),
                                rs.getInt("existing_seats"),
                                rs.getInt("taken_seats"),
                                rs.getString("r_id") == null ? Optional.empty() : Optional.of(mapReservation(rs, "r_"))),
                        params.toArray())
                .stream()
                .findFirst();
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

    /** Reads the reservation and locks its row until the transaction ends, so its owner and status can't change underneath us. */
    public Optional<Reservation> findByIdForUpdate(UUID id) {
        return jdbc.query("""
                                SELECT id, show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash
                                FROM reservations
                                WHERE id = ?
                                FOR UPDATE
                                """,
                        rowMapper, id.toString())
                .stream()
                .findFirst();
    }

    public void markCancelled(UUID id) {
        jdbc.update("""
                        UPDATE reservations
                        SET status = ?, cancelled_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                        """,
                ReservationStatus.CANCELLED.value(), id.toString());
    }

    private Reservation mapReservation(ResultSet rs, String prefix) throws SQLException {
        return new Reservation(
                UUID.fromString(rs.getString(prefix + "id")),
                UUID.fromString(rs.getString(prefix + "show_id")),
                rs.getString(prefix + "user_id"),
                fromJson(rs.getString(prefix + "seats")),
                rs.getLong(prefix + "amount_paise"),
                ReservationStatus.fromValue(rs.getString(prefix + "status")),
                rs.getString(prefix + "idempotency_key"),
                rs.getString(prefix + "request_hash"));
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
