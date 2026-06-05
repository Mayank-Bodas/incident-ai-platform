package com.incidentplatform.infrastructure.persistence.entity;

import com.incidentplatform.domain.enums.Severity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AlertEntity — Raw signals from monitoring systems.
 *
 * WHY separate Alert and Incident tables?
 * Many-to-One relationship: Multiple alerts can trigger ONE incident.
 * Example: CPU spike + memory leak + high error rate → all map to "ServiceXDegraded" incident.
 * This is called "alert correlation" — a key feature of PagerDuty, OpsGenie.
 *
 * Alert = raw signal ("CPU > 90%")
 * Incident = declared problem ("Checkout service degraded") — human/AI facing
 *
 * Interview: "How do you prevent alert storms from creating thousands of incidents?"
 * → Alert deduplication + correlation. We deduplicate by fingerprint within a time window.
 */
@Entity
@Table(
    name = "alerts",
    indexes = {
        @Index(name = "idx_alerts_incident_id", columnList = "incident_id"),
        @Index(name = "idx_alerts_fingerprint", columnList = "fingerprint"),
        // WHY index on fingerprint?
        // Deduplication check: SELECT * FROM alerts WHERE fingerprint = ? AND created_at > NOW() - INTERVAL '10 minutes'
        // Without index: full table scan on every alert. With index: O(log n).
        @Index(name = "idx_alerts_source", columnList = "source")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    // WHY FetchType.LAZY here too?
    // When loading 100 alerts, we don't want to load 100 incidents with them.
    private IncidentEntity incident;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "source", nullable = false, length = 100)
    private String source;  // "prometheus", "datadog", "custom"

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    @Column(name = "environment", length = 50)
    private String environment;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;
    // WHY fingerprint?
    // SHA-256 hash of (source + service + alertType). Used for deduplication.
    // If same alert fires 100 times in 5 minutes, we only create ONE incident.
    // This is how PagerDuty's "alert grouping" works under the hood.

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    // WHY @JdbcTypeCode(SqlTypes.JSON)?
    // Hibernate 6 (Spring Boot 3) requires this annotation to correctly map a
    // Java String to a PostgreSQL JSONB column. Without it, Hibernate sends a
    // plain VARCHAR — PostgreSQL rejects it with "column is of type jsonb but
    // expression is of type character varying".
    // This is the official Hibernate 6 approach — replaces the old custom UserType.
    // WHY JSONB over TEXT?
    // JSONB = binary JSON with GIN index support → fast field-level queries.
    // TEXT = no JSON awareness → must parse on every query.
    private String rawPayload;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "fired_at")
    private LocalDateTime firedAt;  // When the monitoring system detected the issue
}
