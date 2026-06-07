package com.incidentplatform.config;

import com.incidentplatform.domain.enums.UserRole;
import com.incidentplatform.infrastructure.persistence.entity.UserEntity;
import com.incidentplatform.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * DataInitializer — Seeds default users if the users table is empty on startup.
 * Prevents the application database from being empty if wiped by integration tests.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() == 0) {
            log.info("Database users table is empty. Seeding default users...");

            userRepository.save(UserEntity.builder()
                    .email("admin@incidentplatform.com")
                    .passwordHash(passwordEncoder.encode("Admin@123456"))
                    .role(UserRole.ROLE_ADMIN)
                    .firstName("Admin")
                    .lastName("User")
                    .isActive(true)
                    .build());

            userRepository.save(UserEntity.builder()
                    .email("engineer@incidentplatform.com")
                    .passwordHash(passwordEncoder.encode("Admin@123456"))
                    .role(UserRole.ROLE_ENGINEER)
                    .firstName("Jane")
                    .lastName("Engineer")
                    .isActive(true)
                    .build());

            userRepository.save(UserEntity.builder()
                    .email("viewer@incidentplatform.com")
                    .passwordHash(passwordEncoder.encode("Admin@123456"))
                    .role(UserRole.ROLE_VIEWER)
                    .firstName("John")
                    .lastName("Viewer")
                    .isActive(true)
                    .build());

            log.info("Default users seeded successfully.");
        }
    }
}
