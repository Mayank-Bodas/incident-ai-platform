package com.incidentplatform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AuditLogEntity — Immutable record of every significant action.
 *
 * WHY audit logs?
 * 1. Compliance: SOC2, HIPAA, PCI-DSS require audit trails
 * 2. Debugging: "Who changed this incident to RESOLVED at 3am?"
 * 3. Forensics: After an incident, reconstruct the timeline of actions
 *
 * WHY immutable? (no @LastModifiedDate, no update operations)
 * An audit log that can be edited is worthless for compliance.
 * We INSERT only — never UPDATE or DELETE.
 * In production: move to append-only storage (S3, immutable Kafka topic).
 *
 * Interview: "How do you implement audit logging in Spring Boot?"
 * → @EntityListeners + Spring Data Auditing, or better: Hibernate Envers for entity-level change tracking
 */
@Entity
@Table(
    name = "audit_logs",
    indexes = {
        @Index(name = "idx_audit_entity_id", columnList = "entity_id"),
        @Index(name = "idx_audit_performed_by", columnList = "performed_by"),
        @Index(name = "idx_audit_created_at", columnList = "created_at")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;  // "INCIDENT", "ALERT", "USER"

    @Column(name = "entity_id", nullable = false)
    private String entityId;    // ID of the changed entity (String to support UUID/Long)

    @Column(name = "action", nullable = false, length = 100)
    private String action;      // "STATUS_CHANGED", "CREATED", "UPDATED", "DELETED"

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;    // JSON of previous state

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;    // JSON of new state

    @Column(name = "performed_by", length = 255)
    private String performedBy; // Email or "SYSTEM"

    @Column(name = "ip_address", length = 45)
    private String ipAddress;   // IPv4 (15 chars) or IPv6 (39 chars), 45 = safe max

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;   // Note: no @CreatedDate — we set this manually
    // WHY manually? AuditingEntityListener might not fire in all code paths.
    // For audit logs, we set the timestamp explicitly to guarantee accuracy.
}
