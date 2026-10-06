package com.paytm.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiTest extends MySqlIntegrationTest {

    private static final String SHOW_BODY = """
            {"name": "auth-test", "seats": ["A1"], "price_paise": 100}
            """;

    @Test
    void issuesUserTokenWithoutAdminSecret() throws Exception {
        ResponseEntity<String> response = post("/auth/token", "{\"user_id\": \"u-1\"}", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("user_id").asText()).isEqualTo("u-1");
        assertThat(body.get("role").asText()).isEqualTo("user");
        assertThat(body.get("expires_in").asLong()).isEqualTo(3600);
    }

    @Test
    void issuesAdminTokenWithCorrectAdminSecret() throws Exception {
        ResponseEntity<String> response = post("/auth/token",
                "{\"user_id\": \"ops\", \"admin_secret\": \"" + TEST_ADMIN_SECRET + "\"}", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(response.getBody()).get("role").asText()).isEqualTo("admin");
    }

    @Test
    void wrongAdminSecretIs403() {
        ResponseEntity<String> response = post("/auth/token",
                "{\"user_id\": \"ops\", \"admin_secret\": \"guess\"}", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("\"error\":\"forbidden\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"user_id\": \"\"}",
            "{\"user_id\": \"has space\"}",
            "{\"user_id\": \"u@x\"}",
            "{\"user_id\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"
    })
    void invalidUserIdIs400(String requestBody) {
        ResponseEntity<String> response = post("/auth/token", requestBody, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createShowWithAdminTokenIs201() {
        assertThat(post("/shows", SHOW_BODY, adminToken()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void createShowWithUserTokenIs403() {
        ResponseEntity<String> response = post("/shows", SHOW_BODY, userToken("u-2"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createShowWithoutTokenIs401() {
        ResponseEntity<String> response = post("/shows", SHOW_BODY, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.getBody()).contains("\"error\":\"unauthorized\"");
    }

    @Test
    void authIsCheckedBeforeTheBody() {
        assertThat(post("/shows", "{not json", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void garbageTokenIs401() {
        assertThat(post("/shows", SHOW_BODY, "not-a-jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void nonBearerSchemeIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth("admin", TEST_ADMIN_SECRET);

        ResponseEntity<String> response = rest.exchange("/shows", HttpMethod.POST, new HttpEntity<>(SHOW_BODY, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() {
        String forged = Jwts.builder()
                .subject("admin")
                .claim("role", "admin")
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor("attacker-secret-0123456789-0123456789".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(post("/shows", SHOW_BODY, forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenIs401() {
        String expired = Jwts.builder()
                .subject("admin")
                .claim("role", "admin")
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(post("/shows", SHOW_BODY, expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void showStateStaysPublic() throws Exception {
        String id = json.readTree(post("/shows", SHOW_BODY, adminToken()).getBody()).get("id").asText();

        assertThat(rest.getForEntity("/shows/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
