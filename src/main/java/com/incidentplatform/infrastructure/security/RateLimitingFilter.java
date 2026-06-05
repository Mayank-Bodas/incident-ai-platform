package com.incidentplatform.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * RateLimitingFilter — Protects the alert ingestion endpoint from spam and DDOS.
 *
 * WHY register as Component?
 * Spring Boot automatically registers any Filter bean into the Servlet container filter chain.
 *
 * FILTER ORDERING CONTEXT:
 * It executes BEFORE the Spring Security Filter Chain. This is intentional:
 * 1. Cryptographic operations (like parsing and validating JWT signatures in JwtAuthFilter)
 *    are CPU-expensive.
 * 2. By rate-limiting at the Servlet filter entry point, we block unauthorized requests
 *    before executing any authentication logic.
 *
 * Interview: "Where should rate limiting live in the filter chain?"
 * → At the very entrance of the servlet container, before Spring Security.
 *   This prevents CPU exhaustion from JWT cryptographic checks during a DDOS attack.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Rate limit only the alert ingestion endpoint
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/api/v1/alerts".equals(request.getRequestURI())) {
            String clientIp = getClientIp(request);

            if (!rateLimiter.isAllowed(clientIp)) {
                log.warn("Rate limit exceeded for client IP: {}", clientIp);

                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                        "{\"title\":\"Too Many Requests\"," +
                        "\"status\":429," +
                        "\"detail\":\"Alert ingestion rate limit exceeded. Please try again later.\"}"
                );
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        // If request passes through multiple proxies, get the first client IP
        return xfHeader.split(",")[0].trim();
    }
}
