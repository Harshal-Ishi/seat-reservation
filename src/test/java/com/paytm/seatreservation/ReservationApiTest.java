package com.paytm.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationApiTest extends MySqlIntegrationTest {

    @Test
    void reservesASeat() throws Exception {
        String showId = createShow(List.of("A1", "A2"), 25000);

        ResponseEntity<String> response = reserve(showId, userToken("alice"), List.of("A1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("reservation_id").asText()).isNotBlank();
        assertThat(body.get("show_id").asText()).isEqualTo(showId);
        assertThat(body.get("user_id").asText()).isEqualTo("alice");
        assertThat(body.get("seats").get(0).asText()).isEqualTo("A1");
        assertThat(body.get("amount_paise").asLong()).isEqualTo(25000);
        assertThat(body.get("status").asText()).isEqualTo("confirmed");
        assertSeatCounts(showId, 1, 1);
        assertConsistent(showId);
    }

    @Test
    void reservesSeveralSeatsSortedWithTotalAmount() throws Exception {
        String showId = createShow(List.of("A1", "A2", "A3"), 25000);

        ResponseEntity<String> response = reserve(showId, userToken("alice"), List.of("A3", "A1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json.readTree(response.getBody());
        assertThat(json.convertValue(body.get("seats"), List.class)).containsExactly("A1", "A3");
        assertThat(body.get("amount_paise").asLong()).isEqualTo(50000);
        assertConsistent(showId);
    }

    @Test
    void identityComesFromTokenNotBody() throws Exception {
        String showId = createShow(List.of("A1"), 100);

        ResponseEntity<String> response = post("/shows/" + showId + "/reserve",
                "{\"seats\": [\"A1\"], \"user_id\": \"mallory\"}", userToken("alice"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json.readTree(response.getBody()).get("user_id").asText()).isEqualTo("alice");
        assertThat(jdbc.queryForObject("SELECT user_id FROM reservations WHERE show_id = ?", String.class, showId))
                .isEqualTo("alice");
    }

    @Test
    void takenSeatIs409SeatTaken() {
        String showId = createShow(List.of("A1"), 100);
        reserve(showId, userToken("alice"), List.of("A1"));

        ResponseEntity<String> response = reserve(showId, userToken("bob"), List.of("A1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"error\":\"seat-taken\"");
        assertConsistent(showId);
    }

    @Test
    void partialAvailabilityReservesNothing() {
        String showId = createShow(List.of("A1", "A2"), 100);
        reserve(showId, userToken("alice"), List.of("A2"));

        ResponseEntity<String> response = reserve(showId, userToken("bob"), List.of("A1", "A2"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seatStatus(showId, "A1")).isEqualTo("available");
        assertThat(reservationCount(showId)).isEqualTo(1);
        assertConsistent(showId);
    }

    @Test
    void unknownSeatIs400AndReservesNothing() {
        String showId = createShow(List.of("A1"), 100);

        ResponseEntity<String> response = reserve(showId, userToken("alice"), List.of("A1", "Z9"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(seatStatus(showId, "A1")).isEqualTo("available");
        assertThat(reservationCount(showId)).isZero();
    }

    @Test
    void declinedRequestLeavesOtherSeatBookable() {
        String showId = createShow(List.of("A1", "A2"), 100);
        reserve(showId, userToken("alice"), List.of("A2"));
        reserve(showId, userToken("bob"), List.of("A1", "A2"));

        assertThat(reserve(showId, userToken("carol"), List.of("A1")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void withoutTokenIs401() {
        String showId = createShow(List.of("A1"), 100);

        assertThat(reserve(showId, null, List.of("A1")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownShowIs404() {
        ResponseEntity<String> response = reserve("00000000-0000-0000-0000-000000000000", userToken("alice"), List.of("A1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedShowIdIs404() {
        assertThat(reserve("nope", userToken("alice"), List.of("A1")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"seats\": []}",
            "{\"seats\": [\"A1\", \"A1\"]}",
            "{\"seats\": [\"A 1\"]}",
            "{\"seats\": [null]}",
            "{not json"
    })
    void invalidRequestIs400(String requestBody) {
        String showId = createShow(List.of("A1"), 100);

        ResponseEntity<String> response = post("/shows/" + showId + "/reserve", requestBody, userToken("alice"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void assertSeatCounts(String showId, int confirmed, int available) throws Exception {
        JsonNode counts = json.readTree(rest.getForEntity("/shows/" + showId, String.class).getBody()).get("counts");
        assertThat(counts.get("confirmed").asInt()).isEqualTo(confirmed);
        assertThat(counts.get("available").asInt()).isEqualTo(available);
    }

    private String seatStatus(String showId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }

    private int reservationCount(String showId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE show_id = ?", Integer.class, showId);
    }
}
