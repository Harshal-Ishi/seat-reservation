package com.paytm.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends MySqlIntegrationTest {

    // Tests share one database, so keys get a per-test suffix to never collide with another test's keys.
    private final String run = UUID.randomUUID().toString();

    @Test
    void retryWithSameKeyReturnsOriginalWith200() throws Exception {
        String showId = createShow(List.of("A1", "A2"), 100);
        String token = userToken("alice");

        ResponseEntity<String> first = reserve(showId, token, List.of("A1"), key("k-1"));
        ResponseEntity<String> retry = reserve(showId, token, List.of("A1"), key("k-1"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationId(retry)).isEqualTo(reservationId(first));
        assertThat(reservationRows(showId)).isEqualTo(1);
        assertConsistent(showId);
    }

    @Test
    void sameSeatsInAnotherOrderCountAsTheSameRequest() throws Exception {
        String showId = createShow(List.of("A1", "A2"), 100);
        String token = userToken("alice");

        ResponseEntity<String> first = reserve(showId, token, List.of("A1", "A2"), key("k-1"));
        ResponseEntity<String> retry = reserve(showId, token, List.of("A2", "A1"), key("k-1"));

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservationId(retry)).isEqualTo(reservationId(first));
    }

    @Test
    void sameKeyWithDifferentSeatsIs409() {
        String showId = createShow(List.of("A1", "A2"), 100);
        String token = userToken("alice");
        reserve(showId, token, List.of("A1"), key("k-1"));

        ResponseEntity<String> response = reserve(showId, token, List.of("A2"), key("k-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"error\":\"idempotency-key-reused\"");
        assertThat(seatStatus(showId, "A2")).isEqualTo("available");
    }

    @Test
    void sameKeyOnADifferentShowIs409() {
        String showA = createShow(List.of("A1"), 100);
        String showB = createShow(List.of("A1"), 100);
        String token = userToken("alice");
        reserve(showA, token, List.of("A1"), key("k-1"));

        ResponseEntity<String> response = reserve(showB, token, List.of("A1"), key("k-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"error\":\"idempotency-key-reused\"");
    }

    @Test
    void keysAreScopedPerUser() {
        String showId = createShow(List.of("A1", "A2"), 100);

        assertThat(reserve(showId, userToken("alice"), List.of("A1"), key("shared")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(showId, userToken("bob"), List.of("A2"), key("shared")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void declinedAttemptIsNotStoredSoItsRetryIsAFreshAttempt() {
        String showId = createShow(List.of("A1", "A2"), 100);
        String token = userToken("alice");
        reserve(showId, userToken("bob"), List.of("A2"));

        ResponseEntity<String> declined = reserve(showId, token, List.of("A1", "A2"), key("k-1"));
        ResponseEntity<String> retry = reserve(showId, token, List.of("A1"), key("k-1"));

        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void keyCanComeFromTheHeader() {
        String showId = createShow(List.of("A1"), 100);
        String token = userToken("alice");

        ResponseEntity<String> first = reserveWithHeaderKey(showId, token, "{\"seats\": [\"A1\"]}", key("h-1"));
        ResponseEntity<String> retry = reserveWithHeaderKey(showId, token, "{\"seats\": [\"A1\"]}", key("h-1"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bodyAndHeaderKeysMustAgree() {
        String showId = createShow(List.of("A1"), 100);

        ResponseEntity<String> response = reserveWithHeaderKey(showId, userToken("alice"),
                "{\"seats\": [\"A1\"], \"idempotency_key\": \"body-key\"}", key("header-key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingBlankOrOversizedKeyIs400() {
        String showId = createShow(List.of("A1"), 100);
        String token = userToken("alice");

        assertThat(post("/shows/" + showId + "/reserve", "{\"seats\": [\"A1\"]}", token).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reserve(showId, token, List.of("A1"), "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reserve(showId, token, List.of("A1"), "k".repeat(129)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reservationRows(showId)).isZero();
    }

    @Test
    void concurrentRetriesWithOneKeyReserveExactlyOnce() throws Exception {
        String showId = createShow(List.of("A1", "A2"), 100);
        String token = userToken("alice");

        List<ResponseEntity<String>> responses = runConcurrently(50, i -> reserve(showId, token, List.of("A1"), key("same-key")));

        assertNo5xx(responses);
        assertThat(countStatus(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(countStatus(responses, HttpStatus.OK)).isEqualTo(49);
        String winner = reservationId(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).findFirst().orElseThrow());
        for (ResponseEntity<String> r : responses) {
            assertThat(reservationId(r)).isEqualTo(winner);
        }
        assertThat(reservationRows(showId)).isEqualTo(1);
        assertConsistent(showId);
    }

    /**
     * The InnoDB worst case: many transactions insert the same new key, and each one rolls back because the seat is
     * taken. The waiters then deadlock on the key's index entry. The deadlock retry must keep this free of 5xx.
     */
    @Test
    void concurrentRetriesForATakenSeatNeverError() {
        String showId = createShow(List.of("A1"), 100);
        reserve(showId, userToken("bob"), List.of("A1"));
        String token = userToken("alice");

        List<ResponseEntity<String>> responses = runConcurrently(50, i -> reserve(showId, token, List.of("A1"), key("doomed-key")));

        assertNo5xx(responses);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(409, 429));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE user_id = 'alice' AND show_id = ?",
                Integer.class, showId)).isZero();
        assertConsistent(showId);
    }

    @Test
    void manyUsersEachRetryingConcurrentlyGetOneReservationEach() throws Exception {
        List<String> seats = IntStream.rangeClosed(1, 20).mapToObj(n -> "C" + n).toList();
        String showId = createShow(seats, 100);
        List<String> tokens = IntStream.range(0, 20).mapToObj(i -> userToken("retrier-" + i)).toList();

        // Each of 20 users sends the same request 5 times at once.
        List<ResponseEntity<String>> responses = runConcurrently(100,
                i -> reserve(showId, tokens.get(i % 20), List.of(seats.get(i % 20)), key("key-" + (i % 20))));

        assertNo5xx(responses);
        assertThat(countStatus(responses, HttpStatus.CREATED)).isEqualTo(20);
        assertThat(countStatus(responses, HttpStatus.OK)).isEqualTo(80);
        assertThat(reservationRows(showId)).isEqualTo(20);
        assertConsistent(showId);
    }

    private String key(String name) {
        return name + "-" + run;
    }

    private ResponseEntity<String> reserveWithHeaderKey(String showId, String token, String body, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", key);
        return rest.exchange("/shows/" + showId + "/reserve", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String reservationId(ResponseEntity<String> response) throws Exception {
        JsonNode body = json.readTree(response.getBody());
        return body.get("reservation_id").asText();
    }

    private int reservationRows(String showId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE show_id = ?", Integer.class, showId);
    }

    private String seatStatus(String showId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }
}
