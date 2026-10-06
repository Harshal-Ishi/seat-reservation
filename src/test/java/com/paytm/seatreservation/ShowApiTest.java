package com.paytm.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ShowApiTest extends MySqlIntegrationTest {

    @Test
    void createShowReturnsEverySeatAvailable() throws Exception {
        ResponseEntity<String> response = postShow("""
                {"name": "friday-night", "seats": ["A1", "A2", "A10"], "price_paise": 25000}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("id").asText()).isNotBlank();
        assertThat(response.getHeaders().getLocation()).hasToString("/shows/" + body.get("id").asText());
        assertThat(body.get("name").asText()).isEqualTo("friday-night");
        assertThat(body.get("price_paise").asLong()).isEqualTo(25000);
        assertThat(body.get("per_user_limit").asInt()).isEqualTo(4);
        assertThat(body.get("total_seats").asInt()).isEqualTo(3);
        assertCounts(body, 3, 0, 0, 3);
        assertThat(body.get("seats")).allSatisfy(seat -> assertThat(seat.get("status").asText()).isEqualTo("available"));
    }

    @Test
    void getShowReturnsSeatsInCreationOrderWithCounts() throws Exception {
        String id = createShow("""
                {"name": "matinee", "seats": ["A1", "A2", "A10"], "price_paise": 15000, "per_user_limit": 2}
                """);

        ResponseEntity<String> response = rest.getForEntity("/shows/" + id, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("per_user_limit").asInt()).isEqualTo(2);
        assertThat(body.get("seats").findValuesAsText("label")).containsExactly("A1", "A2", "A10");
        assertCounts(body, 3, 0, 0, 3);
    }

    @Test
    void unknownShowIs404() {
        ResponseEntity<String> response = rest.getForEntity("/shows/00000000-0000-0000-0000-000000000000", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"error\":\"not-found\"");
    }

    @Test
    void malformedShowIdIs404() {
        ResponseEntity<String> response = rest.getForEntity("/shows/not-a-uuid", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"seats\": [\"A1\"], \"price_paise\": 100}",
            "{\"name\": \" \", \"seats\": [\"A1\"], \"price_paise\": 100}",
            "{\"name\": \"x\", \"price_paise\": 100}",
            "{\"name\": \"x\", \"seats\": [], \"price_paise\": 100}",
            "{\"name\": \"x\", \"seats\": [\"A1\", \"A1\"], \"price_paise\": 100}",
            "{\"name\": \"x\", \"seats\": [\"A 1\"], \"price_paise\": 100}",
            "{\"name\": \"x\", \"seats\": [\"A1\"]}",
            "{\"name\": \"x\", \"seats\": [\"A1\"], \"price_paise\": -1}",
            "{\"name\": \"x\", \"seats\": [\"A1\"], \"price_paise\": 250.5}",
            "{\"name\": \"x\", \"seats\": [\"A1\"], \"price_paise\": 100, \"per_user_limit\": 0}",
            "{not json"
    })
    void invalidCreateRequestIs400(String requestBody) {
        ResponseEntity<String> response = postShow(requestBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"error\":\"bad-request\"");
    }

    private String createShow(String requestBody) throws Exception {
        ResponseEntity<String> response = postShow(requestBody);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json.readTree(response.getBody()).get("id").asText();
    }

    private ResponseEntity<String> postShow(String requestBody) {
        return post("/shows", requestBody, adminToken());
    }

    private void assertCounts(JsonNode body, int available, int held, int confirmed, int total) {
        JsonNode counts = body.get("counts");
        assertThat(counts.get("available").asInt()).isEqualTo(available);
        assertThat(counts.get("held").asInt()).isEqualTo(held);
        assertThat(counts.get("confirmed").asInt()).isEqualTo(confirmed);
        assertThat(counts.get("total").asInt()).isEqualTo(total);
    }
}
