package com.paytm.seatreservation.controller;

import com.paytm.seatreservation.dto.TokenRequest;
import com.paytm.seatreservation.dto.TokenResponse;
import com.paytm.seatreservation.exception.BadRequestException;
import com.paytm.seatreservation.model.IssuedToken;
import com.paytm.seatreservation.service.TokenService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@RestController
public class AuthController {

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final TokenService tokenService;

    public AuthController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/auth/token")
    public TokenResponse issueToken(@RequestBody TokenRequest request) {
        if (request.userId() == null || !USER_ID.matcher(request.userId()).matches()) {
            throw new BadRequestException("user_id is required: 1-64 characters of A-Z, a-z, 0-9, _ or -");
        }
        IssuedToken issued = tokenService.issueToken(request.userId(), request.adminSecret());
        return new TokenResponse(issued.token(), issued.userId(), issued.role().value(), issued.expiresInSeconds());
    }
}
