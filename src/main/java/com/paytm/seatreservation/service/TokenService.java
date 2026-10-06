package com.paytm.seatreservation.service;

import com.paytm.seatreservation.exception.ForbiddenException;
import com.paytm.seatreservation.exception.UnauthorizedException;
import com.paytm.seatreservation.model.IssuedToken;
import com.paytm.seatreservation.security.AuthenticatedUser;
import com.paytm.seatreservation.security.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies HS256 JWTs. The token's subject is the user id and its "role" claim is user or admin.
 */
@Service
public class TokenService {

    private static final String ROLE_CLAIM = "role";
    // HS256 needs a key of at least 256 bits; a shorter secret would be brute-forceable.
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final byte[] adminSecret;
    private final Duration tokenTtl;

    // @Value without a default fails startup when the env var is missing, so the app can never run on a guessable secret.
    public TokenService(@Value("${JWT_SECRET}") String jwtSecret,
                        @Value("${ADMIN_SECRET}") String adminSecret,
                        @Value("${TOKEN_TTL_SECONDS:3600}") long tokenTtlSeconds) {
        byte[] jwtSecretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (jwtSecretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (adminSecret.isBlank()) {
            throw new IllegalStateException("ADMIN_SECRET must not be blank");
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecretBytes);
        this.adminSecret = adminSecret.getBytes(StandardCharsets.UTF_8);
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
    }

    /**
     * Issues a user token, or an admin token when the correct admin secret is given.
     * Open by design: this endpoint stands in for a real identity provider so a load test can mint many users.
     */
    public IssuedToken issueToken(String userId, String presentedAdminSecret) {
        Role role = Role.USER;
        if (presentedAdminSecret != null) {
            if (!isAdminSecret(presentedAdminSecret)) {
                throw new ForbiddenException("admin_secret is not valid");
            }
            role = Role.ADMIN;
        }

        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(userId)
                .claim(ROLE_CLAIM, role.value())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenTtl)))
                .signWith(signingKey)
                .compact();
        return new IssuedToken(token, userId, role, tokenTtl.toSeconds());
    }

    /** Verifies signature and expiry. Any failure is a 401; the reason is not leaked to the caller. */
    public AuthenticatedUser verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            String role = claims.get(ROLE_CLAIM, String.class);
            if (userId == null || userId.isBlank() || role == null) {
                throw new UnauthorizedException("Invalid token");
            }
            return new AuthenticatedUser(userId, Role.fromValue(role));
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    // Constant-time comparison, so response timing does not reveal how much of the secret matched.
    private boolean isAdminSecret(String presented) {
        return MessageDigest.isEqual(adminSecret, presented.getBytes(StandardCharsets.UTF_8));
    }
}
