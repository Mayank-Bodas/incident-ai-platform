package com.incidentplatform.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JwtAuthenticationFilter — Intercepts HTTP requests to extract and validate JWT tokens.
 *
 * WHY OncePerRequestFilter?
 * Guarantees a single execution per request dispatch.
 * Some servlet containers might route a request through the filter chain multiple times (e.g. forwards).
 * OncePerRequestFilter prevents duplicate processing.
 *
 * Interview: "How does JWT integration work in Spring Security?"
 * → Create a filter that extends OncePerRequestFilter.
 * → Extract Bearer token from Authorization header.
 * → Extract username, load user from DB, validate token.
 * → Set UsernamePasswordAuthenticationToken in SecurityContextHolder.
 * → Place the filter before UsernamePasswordAuthenticationFilter in SecurityFilterChain.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        // Skip filter if header is missing or doesn't start with Bearer
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);

        try {
            userEmail = jwtService.extractUsername(jwt);

            // If user has email and is not authenticated in current security context
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("User authenticated via JWT: email={}, roles={}",
                            userEmail, userDetails.getAuthorities());
                }
            }
        } catch (Exception ex) {
            log.warn("JWT validation failed: {}", ex.getMessage());
            // We do not throw exception here; we let filter chain proceed.
            // Spring Security will automatically handle access control later based on empty context.
        }

        filterChain.doFilter(request, response);
    }
}
