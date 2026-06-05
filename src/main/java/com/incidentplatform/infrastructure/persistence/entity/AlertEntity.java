package com.incidentplatform.infrastructure.persistence.entity;

import com.incidentplatform.domain.enums.Severity;
import jakarta.persistence.*;
import lombok.*;
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

    @Column(name = "raw_payload", columnDefinition = "JSONB")
    // WHY JSONB?
    // Alert payloads differ per source (Prometheus vs Datadog vs custom).
    // We store the raw JSON so we never lose data, even if our schema doesn't model a field.
    // PostgreSQL JSONB: binary JSON with indexing support. Better than TEXT for JSON.
    // Interview: "When would you use JSONB in PostgreSQL?" → Semi-structured data with varying schema
    private String rawPayload;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "fired_at")
    private LocalDateTime firedAt;  // When the monitoring system detected the issue
}
