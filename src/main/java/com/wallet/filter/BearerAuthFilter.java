package com.wallet.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Extracts the authenticated user ID from the Authorization: Bearer <token> header.
 *
 * Per the exercise spec: "a simple bearer token per user identifies the caller."
 * The token IS the user_id — no JWT verification, no token store lookup.
 *
 * The user_id is:
 * - Injected into MDC as "user_id" → appears in every log line for this request
 * - Set as a request attribute "userId" → read by controllers and services
 *
 * Endpoints exempt from auth (public):
 * - GET /health       — liveness probe
 * - GET /dashboard    — metrics dashboard
 * - GET /actuator/**  — Spring Actuator (metrics, prometheus)
 */
public class BearerAuthFilter extends OncePerRequestFilter {

    static final String USER_ID_MDC_KEY = "user_id";
    static final String USER_ID_REQUEST_ATTR = "userId";

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/health", "/dashboard", "/actuator"
    );

    private final ObjectMapper objectMapper;

    public BearerAuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorized(response, "Missing or invalid Authorization header. " +
                    "Expected: Authorization: Bearer <user_id>");
            return;
        }

        String userId = authHeader.substring("Bearer ".length()).trim();
        if (userId.isEmpty()) {
            sendUnauthorized(response, "Bearer token (user_id) must not be empty");
            return;
        }

        MDC.put(USER_ID_MDC_KEY, userId);
        request.setAttribute(USER_ID_REQUEST_ATTR, userId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(USER_ID_MDC_KEY);
        }
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ErrorResponse body = new ErrorResponse("UNAUTHORIZED", message, correlationId);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

