package com.paytm.seatreservation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PerUserLimitTest extends MySqlIntegrationTest {

    private static final List<String> TEN_SEATS = IntStream.rangeClosed(1, 10).mapToObj(n -> "D" + n).toList();

    @Test
    void defaultLimitIsFour() {
        String showId = createShow(TEN_SEATS, 100);
        String token = userToken(uniqueUser());

        assertThat(reserve(showId, token, TEN_SEATS.subList(0, 4)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> fifth = reserve(showId, token, List.of("D5"));

        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fifth.getBody()).contains("\"error\":\"per-user-limit\"");
        assertConsistent(showId);
    }

    @Test
    void limitAddsUpAcrossRequests() {
        String showId = createShow(TEN_SEATS, 100, 2);
        String token = userToken(uniqueUser());

        assertThat(reserve(showId, token, List.of("D1")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(showId, token, List.of("D2")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(showId, token, List.of("D3")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seatStatus(showId, "D3")).isEqualTo("available");
        assertConsistent(showId);
    }

    @Test
    void requestLargerThanLimitIsDeclinedOutright() {
        String showId = createShow(TEN_SEATS, 100, 2);

        ResponseEntity<String> response = reserve(showId, userToken(uniqueUser()), List.of("D1", "D2", "D3"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"error\":\"per-user-limit\"");
        assertThat(seatStatus(showId, "D1")).isEqualTo("available");
    }

    @Test
    void multiSeatRequestThatWouldCrossTheLimitReservesNothing() {
        String showId = createShow(TEN_SEATS, 100, 3);
        String token = userToken(uniqueUser());
        reserve(showId, token, List.of("D1", "D2"));

        ResponseEntity<String> response = reserve(showId, token, List.of("D3", "D4"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seatStatus(showId, "D3")).isEqualTo("available");
        assertThat(seatStatus(showId, "D4")).isEqualTo("available");
        assertConsistent(showId);
    }

    @Test
    void limitIsPerShow() {
        String showA = createShow(TEN_SEATS, 100, 1);
        String showB = createShow(TEN_SEATS, 100, 1);
        String token = userToken(uniqueUser());

        assertThat(reserve(showA, token, List.of("D1")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(showB, token, List.of("D1")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void declinedSeatDoesNotUseUpTheLimit() {
        String showId = createShow(TEN_SEATS, 100, 1);
        reserve(showId, userToken(uniqueUser()), List.of("D1"));
        String token = userToken(uniqueUser());

        assertThat(reserve(showId, token, List.of("D1")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reserve(showId, token, List.of("D2")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertConsistent(showId);
    }

    @Test
    void idempotentReplayDoesNotUseUpTheLimit() {
        String showId = createShow(TEN_SEATS, 100, 1);
        String token = userToken(uniqueUser());
        String key = UUID.randomUUID().toString();

        assertThat(reserve(showId, token, List.of("D1"), key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(showId, token, List.of("D1"), key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertConsistent(showId);
    }

    /** The assignment's check: a user firing 10 parallel reserves on a limit-4 show ends with at most 4 seats. */
    @Test
    void tenParallelReservesOnLimitFourEndWithExactlyFour() {
        String showId = createShow(TEN_SEATS, 100, 4);
        String token = userToken(uniqueUser());

        List<ResponseEntity<String>> responses = runConcurrently(10, i -> reserve(showId, token, List.of(TEN_SEATS.get(i))));

        assertNo5xx(responses);
        assertThat(countStatus(responses, HttpStatus.CREATED)).isEqualTo(4);
        assertThat(countStatus(responses, HttpStatus.CONFLICT)).isEqualTo(6);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .allSatisfy(r -> assertThat(r.getBody()).contains("per-user-limit"));
        assertThat(confirmedSeats(showId)).isEqualTo(4);
        assertConsistent(showId);
    }

    @Test
    void parallelMultiSeatRequestsNeverExceedTheLimit() {
        String showId = createShow(TEN_SEATS, 100, 4);
        String token = userToken(uniqueUser());

        // Five requests of two seats each, all at once: only two fit in a limit of 4.
        List<ResponseEntity<String>> responses = runConcurrently(5,
                i -> reserve(showId, token, List.of(TEN_SEATS.get(2 * i), TEN_SEATS.get(2 * i + 1))));

        assertNo5xx(responses);
        assertThat(countStatus(responses, HttpStatus.CREATED)).isEqualTo(2);
        assertThat(confirmedSeats(showId)).isEqualTo(4);
        assertConsistent(showId);
    }

    @Test
    void manyUsersEachFiringInParallelEachStopAtTheLimit() {
        List<String> seats = IntStream.rangeClosed(1, 300).mapToObj(n -> "E" + n).toList();
        String showId = createShow(seats, 100, 4);
        int users = 25;
        List<String> tokens = IntStream.range(0, users).mapToObj(i -> userToken(uniqueUser())).toList();

        // 25 users × 10 parallel requests each, every request for a different seat.
        List<ResponseEntity<String>> responses = runConcurrently(users * 10,
                i -> reserve(showId, tokens.get(i % users), List.of(seats.get(i))));

        assertNo5xx(responses);
        assertThat(countStatus(responses, HttpStatus.CREATED)).isEqualTo(users * 4L);
        assertThat(jdbc.queryForObject(
                "SELECT MAX(seat_count) FROM user_seat_counts WHERE show_id = ?", Integer.class, showId)).isEqualTo(4);
        assertConsistent(showId);
    }

    private String uniqueUser() {
        return "lim-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private int confirmedSeats(String showId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", Integer.class, showId);
    }

    private String seatStatus(String showId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }
}
