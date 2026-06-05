package com.incidentplatform.config;

import com.incidentplatform.infrastructure.security.CustomUserDetailsService;
import com.incidentplatform.infrastructure.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SecurityConfig — Spring Security configuration.
 *
 * WHY @EnableMethodSecurity?
 * Enables annotation-based security like @PreAuthorize("hasRole('ADMIN')") on controller methods.
 * Provides fine-grained control when requestMatcher rules are not granular enough.
 *
 * WHY SessionCreationPolicy.STATELESS?
 * JWT-based authentication is stateless. We do not store session state on the server.
 * Disables session cookie creation and HttpSession persistence in Spring Security context.
 *
 * Interview: "How do you secure a REST API with Spring Security and JWT?"
 * - Disable CSRF (since JWT is stateless, not cookie-based).
 * - Set session creation policy to STATELESS.
 * - Add a custom JWT filter before UsernamePasswordAuthenticationFilter.
 * - Configure requestMatchers for roles (e.g. hasRole("ADMIN") or hasAnyRole("ADMIN", "ENGINEER")).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 1. Public endpoints (auth, health, OpenAPI docs)
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/api-docs/**",
                    "/api/v1/auth/**"
                ).permitAll()

                // 2. Alert Ingestion: ADMIN and ENGINEER can ingest alerts.
                .requestMatchers(HttpMethod.POST, "/api/v1/alerts").hasAnyRole("ADMIN", "ENGINEER")

                // 3. Incident Retrieval: VIEWERS, ENGINEERS, and ADMINS can view incidents.
                .requestMatchers(HttpMethod.GET, "/api/v1/incidents/**").hasAnyRole("ADMIN", "ENGINEER", "VIEWER")

                // 4. Incident Mutation: ADMIN and ENGINEER can create/update incidents or status.
                .requestMatchers(HttpMethod.POST, "/api/v1/incidents/**").hasAnyRole("ADMIN", "ENGINEER")
                .requestMatchers(HttpMethod.PUT, "/api/v1/incidents/**").hasAnyRole("ADMIN", "ENGINEER")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/incidents/**").hasAnyRole("ADMIN", "ENGINEER")

                // 5. Incident Deletion: Only ADMIN can delete incidents.
                .requestMatchers(HttpMethod.DELETE, "/api/v1/incidents/**").hasRole("ADMIN")

                // All other endpoints require authentication
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost factor 12: good balance of security vs performance
        return new BCryptPasswordEncoder(12);
    }
}

