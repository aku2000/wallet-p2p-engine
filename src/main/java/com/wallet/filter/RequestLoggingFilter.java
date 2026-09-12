package com.wallet.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Logs a structured http.request event after every HTTP request completes.
 *
 * Runs AFTER auth filter so user_id is already in MDC when the log line is written.
 * The correlation_id set by CorrelationIdFilter is also in MDC, so both fields
 * appear automatically in the log line via logstash-logback-encoder.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(request, responseWrapper);
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            int status = responseWrapper.getStatus();
            String method = request.getMethod();
            String path = request.getRequestURI();

            log.info("{} {} {} {}ms",
                    method, path, status, durationMs,
                    kv("event", "http.request"),
                    kv("method", method),
                    kv("path", path),
                    kv("status", status),
                    kv("duration_ms", durationMs)
            );

            // Must copy body to actual response (ContentCachingResponseWrapper buffers it)
            responseWrapper.copyBodyToResponse();
        }
    }
}

