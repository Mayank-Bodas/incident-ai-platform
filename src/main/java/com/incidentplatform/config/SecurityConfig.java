package com.incidentplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityConfig — Spring Security configuration.
 *
 * WHY @Configuration + @EnableWebSecurity?
 * @Configuration: marks as Spring config class (beans defined here)
 * @EnableWebSecurity: activates Spring Security's web security support
 *
 * NOTE: This is a DEVELOPMENT config — all endpoints are open.
 * On Day 4 we replace this with full JWT authentication.
 * @Profile("!prod") ensures this permissive config NEVER loads in production.
 *
 * WHY SecurityFilterChain (not WebSecurityConfigurerAdapter)?
 * WebSecurityConfigurerAdapter was deprecated in Spring Security 5.7.
 * Spring Boot 3 uses the new SecurityFilterChain bean approach.
 * Always use SecurityFilterChain in Spring Boot 3+ projects.
 * Interview: "How do you configure Spring Security?" → SecurityFilterChain bean, not adapter.
 *
 * WHY BCryptPasswordEncoder?
 * BCrypt is the industry standard for password hashing.
 * Cost factor 12 = ~300ms to hash one password.
 * Brute force: GPU can try 10B+ MD5/sec but only ~3000 BCrypt/sec.
 * The slow speed is INTENTIONAL and DESIRED for passwords.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // WHY disable CSRF?
            // CSRF attacks exploit browser cookie-based sessions.
            // We use JWT (stateless) — no sessions, no CSRF risk.
            // For cookie-based auth: ALWAYS enable CSRF.
            // Interview: "When do you disable CSRF?" → Stateless REST APIs with JWT
            .authorizeHttpRequests(auth -> auth
                // Public endpoints: health, docs, auth
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/api-docs/**",
                    "/api/v1/auth/**"
                ).permitAll()
                // TODO Day 4: Lock down API endpoints by role
                // .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // .requestMatchers(HttpMethod.POST, "/api/v1/incidents/**").hasAnyRole("ADMIN", "ENGINEER")
                .anyRequest().permitAll()  // TEMPORARY — replaced on Day 4
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost factor 12: good balance of security vs performance
        // Cost 10: ~100ms, Cost 12: ~300ms, Cost 14: ~1200ms
        // Higher cost = more brute-force resistant, but slower login
        return new BCryptPasswordEncoder(12);
    }
}
