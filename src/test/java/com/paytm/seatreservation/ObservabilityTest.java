package com.paytm.seatreservation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest extends MySqlIntegrationTest {

    @Test
    void prometheusExposesTheBusinessMetrics() {
        createShow(List.of("A1"), 100);

        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("reservations_confirmed_total")
                .contains("reservations_cancelled_total")
                .contains("reservations_declined_total{reason=\"seat-taken\"}")
                .contains("reservations_declined_total{reason=\"per-user-limit\"}")
                .contains("reservations_declined_total{reason=\"idempotent-replay\"}")
                .contains("seats_available{show_id=")
                .contains("http_server_requests_seconds_count");
    }

    /** Every outcome moves exactly its own counter by one, so the metrics reconcile with what the API returned. */
    @Test
    void countersMoveExactlyWithTheOutcomes() {
        String showId = createShow(List.of("A1", "A2", "A3"), 100, 1);
        String alice = userToken("obs-" + UUID.randomUUID().toString().substring(0, 8));
        String bob = userToken("obs-" + UUID.randomUUID().toString().substring(0, 8));
        String key = UUID.randomUUID().toString();
        double confirmed = counter("reservations_confirmed_total");
        double cancelled = counter("reservations_cancelled_total");
        double seatTaken = declined("seat-taken");
        double limit = declined("per-user-limit");
        double replay = declined("idempotent-replay");
        double keyReused = declined("idempotency-key-reused");

        String reservationId = reservationIdOf(reserve(showId, alice, List.of("A1"), key)); // confirmed
        reserve(showId, alice, List.of("A1"), key);                                       // idempotent replay (200)
        reserve(showId, alice, List.of("A2"), key);                                       // key reused (409)
        reserve(showId, alice, List.of("A2"));                                            // per-user limit (409)
        reserve(showId, bob, List.of("A1"));                                              // seat taken (409)
        post("/reservations/" + reservationId + "/cancel", "", alice);                    // cancelled
        post("/reservations/" + reservationId + "/cancel", "", alice);                    // no-op, not counted again

        assertThat(counter("reservations_confirmed_total") - confirmed).isEqualTo(1);
        assertThat(counter("reservations_cancelled_total") - cancelled).isEqualTo(1);
        assertThat(declined("seat-taken") - seatTaken).isEqualTo(1);
        assertThat(declined("per-user-limit") - limit).isEqualTo(1);
        assertThat(declined("idempotent-replay") - replay).isEqualTo(1);
        assertThat(declined("idempotency-key-reused") - keyReused).isEqualTo(1);
    }

    @Test
    void seatsAvailableGaugeMatchesTheApi() throws Exception {
        String showId = createShow(List.of("A1", "A2", "A3"), 100);
        reserve(showId, userToken("gauge-user"), List.of("A1", "A2"));

        int fromApi = json.readTree(rest.getForEntity("/shows/" + showId, String.class).getBody())
                .get("counts").get("available").asInt();

        assertThat(fromApi).isEqualTo(1);
        assertThat(metric("seats_available{show_id=\"" + showId + "\"}")).isEqualTo(1);
    }

    @Test
    void everyResponseCarriesARequestId() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void wellFormedIncomingRequestIdIsKeptAndEchoedInErrors() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "trace-abc-123");

        ResponseEntity<String> response = rest.exchange("/shows/not-a-uuid", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-abc-123");
        assertThat(response.getBody()).contains("\"request_id\":\"trace-abc-123\"");
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "bad id with spaces");

        ResponseEntity<String> response = rest.exchange("/actuator/health/liveness", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotEqualTo("bad id with spaces").isNotBlank();
    }

    @Test
    void reserveOutcomeIsOneStructuredLogLineWithRequestAndUserIds(CapturedOutput output) {
        String showId = createShow(List.of("A1"), 100);
        String userId = "log-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken(userId));
        headers.set("Content-Type", "application/json");
        headers.set("X-Request-Id", "log-test-1");

        rest.exchange("/shows/" + showId + "/reserve", HttpMethod.POST,
                new HttpEntity<>("{\"seats\": [\"A1\"], \"idempotency_key\": \"" + UUID.randomUUID() + "\"}", headers), String.class);

        String line = output.getOut().lines()
                .filter(l -> l.contains("\"request_id\":\"log-test-1\"") && l.contains("\"outcome\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No outcome log line for the request"));
        assertThat(line)
                .startsWith("{")
                .contains("\"outcome\":\"confirmed\"")
                .contains("\"user_id\":\"" + userId + "\"")
                .contains("\"show_id\":\"" + showId + "\"")
                .contains("\"seat_count\":1");
        assertThat(line).doesNotContain("Bearer");
    }

    private double counter(String name) {
        return metric(name);
    }

    private double declined(String reason) {
        return metric("reservations_declined_total{reason=\"" + reason + "\"}");
    }

    /** Reads one sample from the Prometheus text format: the line that starts with the given name and labels. */
    private double metric(String nameWithLabels) {
        String body = rest.getForEntity("/actuator/prometheus", String.class).getBody();
        return body.lines()
                .filter(line -> line.startsWith(nameWithLabels + " "))
                .map(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Metric not found: " + nameWithLabels));
    }

    private String reservationIdOf(ResponseEntity<String> response) {
        try {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return json.readTree(response.getBody()).get("reservation_id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
