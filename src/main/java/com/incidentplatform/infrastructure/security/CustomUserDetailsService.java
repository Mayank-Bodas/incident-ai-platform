package com.incidentplatform.infrastructure.security;

import com.incidentplatform.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * CustomUserDetailsService — Loads user from PostgreSQL database.
 * Used by Spring Security authentication provider.
 *
 * WHY implements UserDetailsService?
 * Spring Security's core interface to retrieve user authentication/authorization info.
 * Avoids default in-memory user configuration.
 *
 * Interview: "How does Spring Security load users from database?"
 * - Implement UserDetailsService, override loadUserByUsername, and return a UserDetails object.
 * - Configure authentication manager/provider to use this service.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
    }
}
