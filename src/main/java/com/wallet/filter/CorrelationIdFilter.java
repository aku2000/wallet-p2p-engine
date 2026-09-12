package com.wallet.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects a correlation ID into every request for end-to-end tracing.
 *
 * The correlation ID appears in every log line via MDC (Mapped Diagnostic Context),
 * is echoed in the X-Correlation-ID response header, and is included in all
 * ErrorResponse bodies so clients can reference it when reporting issues.
 *
 * Priority: reads X-Correlation-ID from request header if provided by the client
 * (useful for distributed tracing across services), otherwise generates a new UUID.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    static final String MDC_KEY = "correlation_id";
    static final String REQUEST_ATTR = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        request.setAttribute(REQUEST_ATTR, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Always clear MDC to avoid leaking state to pooled threads
            MDC.remove(MDC_KEY);
        }
    }
}

