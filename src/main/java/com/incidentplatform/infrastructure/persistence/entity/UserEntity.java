package com.incidentplatform.infrastructure.persistence.entity;

import com.incidentplatform.domain.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * UserEntity — System users with roles for RBAC.
 *
 * SECURITY NOTE: passwords are stored as BCrypt hashes, NEVER plaintext.
 * BCrypt is a one-way function — you cannot recover the original password.
 * Interview: "Why BCrypt over MD5/SHA-256 for passwords?"
 * → BCrypt has configurable cost factor (work factor), making brute force exponentially harder.
 * → MD5/SHA-256 are fast — bad for passwords. BCrypt is slow by design.
 * → Salt is embedded in the hash — prevents rainbow table attacks.
 */
@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "idx_users_email", columnList = "email", unique = true)
        // WHY unique index on email?
        // Email is the login identifier. Uniqueness enforced at DB level (not just app level).
        // Defence in depth: even if app validation fails, DB constraint catches it.
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;  // BCrypt hash — NEVER store plaintext

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
