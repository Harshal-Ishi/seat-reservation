package com.paytm.seatreservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class HealthEndpointsTest extends MySqlIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void livenessIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readinessIsUpWhenDatabaseIsReachable() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"db\":{\"status\":\"UP\"");
    }
}
