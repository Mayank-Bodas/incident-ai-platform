package com.incidentplatform.infrastructure.persistence.entity;

import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * IncidentEntity — JPA Entity for the incidents table.
 *
 * WHY is this in infrastructure.persistence, not domain?
 * CLEAN ARCHITECTURE RULE: Domain layer has NO framework dependencies.
 * JPA annotations (@Entity, @Column) are Spring/Hibernate framework concerns.
 * The domain model (Incident) is a pure Java class with business logic.
 * The entity is the DB representation — they are SEPARATE classes.
 *
 * WHY @EntityListeners(AuditingEntityListener.class)?
 * Enables JPA Auditing. Spring auto-sets @CreatedDate and @LastModifiedDate.
 * Without this, you'd manually set createdAt = LocalDateTime.now() everywhere.
 * DRY principle: Don't repeat yourself.
 *
 * WHY UUID as primary key?
 * - Globally unique across distributed systems
 * - No sequential guessing (security)
 * - Safe to generate client-side before DB insert
 * - Downside: index fragmentation (B-tree doesn't like random UUIDs)
 * - Modern PostgreSQL UUIDv7 solves this (time-ordered) — advanced topic
 *
 * Interview: "UUID vs Auto-increment? Which is better?"
 * → Depends. UUIDs for distributed/multi-shard. Auto-increment for single-node with max performance.
 */
@Entity
@Table(
    name = "incidents",
    indexes = {
        // WHY indexes here?
        // @Index creates a DB index on this column.
        // Without index on status: querying active incidents = full table scan.
        // With index: O(log n) B-tree lookup.
        // Interview: "How do you optimize slow queries?" → Add indexes on WHERE/ORDER BY columns
        @Index(name = "idx_incidents_status", columnList = "status"),
        @Index(name = "idx_incidents_severity", columnList = "severity"),
        @Index(name = "idx_incidents_created_at", columnList = "created_at")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// WHY @Builder (Lombok)?
// Builder pattern: creates complex objects step-by-step.
// IncidentEntity.builder().title("DB down").severity(SEV1).build()
// Avoids telescoping constructors. SOLID: Open for extension.
public class IncidentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    // WHY GenerationType.UUID?
    // Hibernate generates UUID before INSERT, not after (unlike IDENTITY/SEQUENCE).
    // This means we know the ID before hitting the DB — useful for Kafka events.
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    // WHY columnDefinition = "TEXT"?
    // Default VARCHAR(255) truncates long descriptions. TEXT = unlimited.
    private String description;

    @Enumerated(EnumType.STRING)
    // WHY EnumType.STRING, not ORDINAL?
    // ORDINAL stores 0,1,2,3 — if you reorder enum, data corrupts silently.
    // STRING stores "OPEN","INVESTIGATING" — refactor-safe.
    @Column(name = "status", nullable = false)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "service_name", nullable = false, length = 255)
    private String serviceName;

    @Column(name = "environment", length = 50)
    private String environment;  // production, staging, development

    @Column(name = "rca_summary", columnDefinition = "TEXT")
    private String rcaSummary;   // Root Cause Analysis — populated by RCA Agent

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // WHY @CreatedDate / @LastModifiedDate?
    // JPA Auditing auto-populates these. No manual code needed.
    // updatable = false on createdAt: once set, never changes (immutable audit trail).
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;   // Username of who created (or "SYSTEM" for auto-created)

    // WHY @OneToMany with cascade?
    // One incident has many alerts. CascadeType.ALL = CRUD operations cascade to alerts.
    // orphanRemoval = if alert removed from list, delete from DB.
    // Interview: "Explain JPA cascade types" — common interview question
    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    // WHY FetchType.LAZY?
    // EAGER loads alerts with EVERY incident query — even when you only need the title.
    // LAZY loads alerts only when you access incident.getAlerts() — much more efficient.
    // N+1 problem: use @EntityGraph or JOIN FETCH when you do need alerts.
    @Builder.Default
    private List<AlertEntity> alerts = new ArrayList<>();

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<InvestigationResultEntity> investigationResults = new ArrayList<>();

    /**
     * Business logic method — lives on the entity (Domain Model / Rich Domain pattern).
     *
     * WHY put business logic on the entity?
     * Two schools of thought:
     * 1. Anemic Domain Model: entities are dumb data bags, logic in services
     * 2. Rich Domain Model (DDD): entities contain business rules
     *
     * We use Rich Domain: the rule "INVESTIGATING requires OPEN status" belongs
     * on the Incident, not scattered across services.
     * Interview: "What is Anemic vs Rich Domain Model?" → Very common DDD question
     */
    public boolean canTransitionTo(IncidentStatus newStatus) {
        return switch (this.status) {
            case OPEN -> newStatus == IncidentStatus.INVESTIGATING;
            case INVESTIGATING -> newStatus == IncidentStatus.RESOLVED;
            case RESOLVED -> newStatus == IncidentStatus.CLOSED || newStatus == IncidentStatus.OPEN;
            case CLOSED -> false; // Terminal state — no transitions out
        };
    }
}
