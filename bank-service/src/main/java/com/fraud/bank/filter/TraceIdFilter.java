package com.fraud.bank.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * TraceIdFilter — Distributed Trace ID propagation via SLF4J MDC.
 *
 * Runs at HIGHEST_PRECEDENCE so the traceId is in MDC for every log line.
 *
 * Behaviour:
 *   1. Read X-Trace-Id from the inbound request header (set by API Gateway or callers).
 *   2. If absent or blank, generate a new UUID v4 as a fallback.
 *   3. Store in MDC under "traceId" — referenced by the logging pattern:
 *      [traceId=%X{traceId:-NO_TRACE}]
 *   4. Echo the resolved traceId back in the response header X-Trace-Id.
 *   5. Always clear MDC in the finally block to prevent thread-pool leakage.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_HEADER  = "X-Trace-Id";
    private static final String MDC_TRACE_KEY = "traceId";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_TRACE_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
