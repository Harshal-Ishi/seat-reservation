package com.paytm.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for tests against a real MySQL in Docker. Skipped when Docker is not available.
 *
 * One container is shared by every subclass. It is started lazily here rather than with @Container,
 * because @Container would stop it after each test class while Spring keeps the cached context
 * pointing at it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=" + MySqlIntegrationTest.TEST_JWT_SECRET,
                "ADMIN_SECRET=" + MySqlIntegrationTest.TEST_ADMIN_SECRET
        })
public abstract class MySqlIntegrationTest {

    static final String TEST_JWT_SECRET = "test-jwt-secret-0123456789-0123456789";
    static final String TEST_ADMIN_SECRET = "test-admin-secret";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USERNAME", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
    }

    protected String userToken(String userId) {
        return mintToken("{\"user_id\": \"" + userId + "\"}");
    }

    protected String adminToken() {
        return mintToken("{\"user_id\": \"admin\", \"admin_secret\": \"" + TEST_ADMIN_SECRET + "\"}");
    }

    protected ResponseEntity<String> post(String path, String body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    /** Creates a show as admin and returns its id. */
    protected String createShow(List<String> seats, long pricePaise) {
        try {
            String body = json.writeValueAsString(Map.of("name", "test-show", "seats", seats, "price_paise", pricePaise));
            ResponseEntity<String> response = post("/shows", body, adminToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            return json.readTree(response.getBody()).get("id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected ResponseEntity<String> reserve(String showId, String token, List<String> seats) {
        try {
            return post("/shows/" + showId + "/reserve", json.writeValueAsString(Map.of("seats", seats)), token);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks the show's state straight from the database: every seat is in exactly one state, and the
     * confirmed seats are exactly the seats listed on confirmed reservations (no partial or orphan bookings).
     */
    protected void assertConsistent(String showId) {
        Integer total = jdbc.queryForObject("SELECT total_seats FROM shows WHERE id = ?", Integer.class, showId);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id = ?", Integer.class, showId);
        Integer stateSum = jdbc.queryForObject("""
                SELECT SUM(status = 'available') + SUM(status = 'held') + SUM(status = 'confirmed')
                FROM seats WHERE show_id = ?
                """, Integer.class, showId);
        Integer confirmedSeats = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", Integer.class, showId);
        Integer seatsOnReservations = jdbc.queryForObject("""
                SELECT COALESCE(SUM(JSON_LENGTH(seats)), 0)
                FROM reservations WHERE show_id = ? AND status = 'confirmed'
                """, Integer.class, showId);
        Integer seatsPointingAtConfirmedReservations = jdbc.queryForObject("""
                SELECT COUNT(*) FROM seats s
                JOIN reservations r ON r.id = s.reservation_id AND r.status = 'confirmed'
                WHERE s.show_id = ?
                """, Integer.class, showId);

        assertThat(rows).isEqualTo(total);
        assertThat(stateSum).isEqualTo(total);
        assertThat(seatsOnReservations).isEqualTo(confirmedSeats);
        assertThat(seatsPointingAtConfirmedReservations).isEqualTo(confirmedSeats);
    }

    private String mintToken(String requestBody) {
        ResponseEntity<String> response = post("/auth/token", requestBody, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode body = json.readTree(response.getBody());
            return body.get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Token response was not JSON: " + response.getBody(), e);
        }
    }
}
