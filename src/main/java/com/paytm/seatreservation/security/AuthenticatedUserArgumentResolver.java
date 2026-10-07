package com.paytm.seatreservation.security;

import com.paytm.seatreservation.exception.UnauthorizedException;
import com.paytm.seatreservation.observability.RequestIdFilter;
import org.slf4j.MDC;
import com.paytm.seatreservation.service.TokenService;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies an {@link AuthenticatedUser} to any controller method that declares one, from the
 * {@code Authorization: Bearer <jwt>} header. Endpoints without that parameter stay public.
 * Failures are thrown as exceptions so they get the same JSON error body as every other error.
 */
@Component
public class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public AuthenticatedUserArgumentResolver(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AuthenticatedUser.class);
    }

    @Override
    public AuthenticatedUser resolveArgument(MethodParameter parameter,
                                             ModelAndViewContainer mavContainer,
                                             NativeWebRequest webRequest,
                                             WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Missing bearer token");
        }
        AuthenticatedUser user = tokenService.verify(header.substring(BEARER_PREFIX.length()).trim());
        // From here on, every log line of this request carries the verified user id.
        MDC.put(RequestIdFilter.MDC_USER_ID, user.userId());
        return user;
    }
}
