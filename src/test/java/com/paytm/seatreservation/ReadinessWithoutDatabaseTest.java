package com.paytm.seatreservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Points the app at a port where nothing listens, so it runs without Docker.
 * Flyway is off because migrations need a database at startup; this test is only about the probes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "DB_URL=jdbc:mysql://localhost:1/unreachable",
                "DB_USERNAME=unused",
                "DB_PASSWORD=unused",
                "DB_CONNECTION_TIMEOUT_MS=1000",
                "spring.flyway.enabled=false",
                "JWT_SECRET=test-jwt-secret-0123456789-0123456789",
                "ADMIN_SECRET=test-admin-secret"
        })
class ReadinessWithoutDatabaseTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void livenessStaysUpWhenDatabaseIsDown() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessFailsClosedWhenDatabaseIsDown() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("\"status\":\"DOWN\"");
    }
}
