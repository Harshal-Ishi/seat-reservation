package com.paytm.seatreservation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CancelTest extends MySqlIntegrationTest {

    @Test
    void ownerCancelsAndSeatsBecomeAvailable() throws Exception {
        String showId = createShow(List.of("A1", "A2", "A3"), 100);
        String token = userToken(uniqueUser());
        String reservationId = reservationId(reserve(showId, token, List.of("A1", "A2")));

        ResponseEntity<String> response = cancel(reservationId, token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).get("status").asText()).isEqualTo("cancelled");
        assertThat(json.readTree(response.getBody()).get("reservation_id").asText()).isEqualTo(reservationId);
        assertThat(seatStatus(showId, "A1")).isEqualTo("available");
        assertThat(seatStatus(showId, "A2")).isEqualTo("available");
        assertConsistent(showId);
    }

    @Test
    void releasedSeatIsCleanlyRebookableByAnotherUser() throws Exception {
        String showId = createShow(List.of("A1"), 100);
        String alice = userToken(uniqueUser());
        cancel(reservationId(reserve(showId, alice, List.of("A1"))), alice);

        assertThat(reserve(showId, userToken(uniqueUser()), List.of("A1")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertConsistent(showId);
    }

    @Test
    void cancelGivesQuotaBack() throws Exception {
        String showId = createShow(List.of("A1", "A2"), 100, 1);
        String token = userToken(uniqueUser());
        cancel(reservationId(reserve(showId, token, List.of("A1"))), token);

        assertThat(reserve(showId, token, List.of("A2")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertConsistent(showId);
    }

    @Test
    void cancellingTwiceReturns200AndChangesNothingMore() throws Exception {
        String showId = createShow(List.of("A1"), 100);
        String token = userToken(uniqueUser());
        String reservationId = reservationId(reserve(showId, token, List.of("A1")));

        assertThat(cancel(reservationId, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> again = cancel(reservationId, token);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(again.getBody()).get("status").asText()).isEqualTo("cancelled");
        assertConsistent(showId);
    }

    @Test
    void anotherUserCannotCancel() throws Exception {
        String showId = createShow(List.of("A1"), 100);
        String reservationId = reservationId(reserve(showId, userToken(uniqueUser()), List.of("A1")));

        ResponseEntity<String> response = cancel(reservationId, userToken(uniqueUser()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(seatStatus(showId, "A1")).isEqualTo("confirmed");
        assertConsistent(showId);
    }

    @Test
    void adminTokenIsNotTheOwnerEither() throws Exception {
        String showId = createShow(List.of("A1"), 100);
        String reservationId = reservationId(reserve(showId, userToken(uniqueUser()), List.of("A1")));

        assertThat(cancel(reservationId, adminToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownOrMalformedIdIs404AndNoTokenIs401() {
        String token = userToken(uniqueUser());

        assertThat(cancel(UUID.randomUUID().toString(), token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancel("nope", token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancel(UUID.randomUUID().toString(), null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void replayingTheReserveKeyAfterCancelReturnsTheCancelledReservationAndBooksNothing() throws Exception {
        String showId = createShow(List.of("A1"), 100);
        String token = userToken(uniqueUser());
        String key = UUID.randomUUID().toString();
        String reservationId = reservationId(reserve(showId, token, List.of("A1"), key));
        cancel(reservationId, token);

        ResponseEntity<String> replay = reserve(showId, token, List.of("A1"), key);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(replay.getBody()).get("status").asText()).isEqualTo("cancelled");
        assertThat(seatStatus(showId, "A1")).isEqualTo("available");
        assertConsistent(showId);
    }

    @Test
    void lateCancelNeverResurrectsASeatNowOwnedBySomeoneElse() throws Exception {
        String showId = createShow(List.of("A1"), 100);
        String alice = userToken(uniqueUser());
        String aliceReservation = reservationId(reserve(showId, alice, List.of("A1")));
        cancel(aliceReservation, alice);
        String bobReservation = reservationId(reserve(showId, userToken(uniqueUser()), List.of("A1")));

        // Alice's client retries its old cancel after Bob has the seat.
        assertThat(cancel(aliceReservation, alice).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(seatStatus(showId, "A1")).isEqualTo("confirmed");
        assertThat(seatOwnerReservation(showId, "A1")).isEqualTo(bobReservation);
        assertConsistent(showId);
    }

    @Test
    void parallelCancelsOfOneReservationReleaseOnce() throws Exception {
        String showId = createShow(List.of("A1", "A2"), 100);
        String token = userToken(uniqueUser());
        String reservationId = reservationId(reserve(showId, token, List.of("A1", "A2")));

        List<ResponseEntity<String>> responses = runConcurrently(30, i -> cancel(reservationId, token));

        assertNo5xx(responses);
        assertThat(countStatus(responses, HttpStatus.OK)).isEqualTo(30);
        assertConsistent(showId);
    }

    /** The owner cancels while 50 others storm the same seat: at most one of them gets it, and nothing is lost. */
    @Test
    void cancelRacingAHotSeatStormHandsTheSeatToAtMostOneBuyer() throws Exception {
        String showId = createShow(List.of("A1"), 100);
        String owner = userToken(uniqueUser());
        String reservationId = reservationId(reserve(showId, owner, List.of("A1")));
        List<String> buyers = IntStream.range(0, 50).mapToObj(i -> userToken(uniqueUser())).toList();

        List<ResponseEntity<String>> responses = runConcurrently(51,
                i -> i == 0 ? cancel(reservationId, owner) : reserve(showId, buyers.get(i - 1), List.of("A1")));

        assertNo5xx(responses);
        assertThat(responses.get(0).getStatusCode()).isEqualTo(HttpStatus.OK);
        long winners = responses.subList(1, 51).stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        assertThat(winners).isLessThanOrEqualTo(1);
        assertThat(seatStatus(showId, "A1")).isEqualTo(winners == 1 ? "confirmed" : "available");
        assertConsistent(showId);
    }

    /** Many users reserving and cancelling at once; afterwards every seat, reservation and counter must agree. */
    @Test
    void reserveAndCancelChurnStaysConsistent() throws Exception {
        List<String> seats = IntStream.rangeClosed(1, 20).mapToObj(n -> "F" + n).toList();
        String showId = createShow(seats, 100, 4);
        List<String> tokens = IntStream.range(0, 20).mapToObj(i -> userToken(uniqueUser())).toList();
        // Round 1: every user takes one seat.
        List<String> firstReservations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            firstReservations.add(reservationId(reserve(showId, tokens.get(i), List.of(seats.get(i)))));
        }

        // Round 2, all at once: each user cancels their seat while also trying to grab a neighbour's.
        List<ResponseEntity<String>> responses = runConcurrently(40, i -> i % 2 == 0
                ? cancel(firstReservations.get(i / 2), tokens.get(i / 2))
                : reserve(showId, tokens.get(i / 2), List.of(seats.get((i / 2 + 1) % 20))));

        assertNo5xx(responses);
        assertConsistent(showId);
    }

    private ResponseEntity<String> cancel(String reservationId, String token) {
        return post("/reservations/" + reservationId + "/cancel", "", token);
    }

    private String reservationId(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(response.getBody()).get("reservation_id").asText();
    }

    private String uniqueUser() {
        return "c-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String seatStatus(String showId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }

    private String seatOwnerReservation(String showId, String label) {
        return jdbc.queryForObject("SELECT reservation_id FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }
}
