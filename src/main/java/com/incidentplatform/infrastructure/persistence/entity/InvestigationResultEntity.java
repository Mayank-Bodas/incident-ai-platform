package com.incidentplatform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * InvestigationResultEntity — Stores each AI agent's output.
 *
 * WHY a separate table for investigation results?
 * One incident can have multiple investigation runs (e.g., re-triggered after new alert).
 * One investigation run has one result per agent (6 agents = 6 rows).
 * Storing results separately enables:
 *   - Audit of what each agent concluded
 *   - Comparing results across multiple investigations
 *   - Replaying investigation with different agent versions
 *
 * This is the foundation for "explainable AI" — users can see WHY the AI concluded something.
 * Interview: "How do you make your AI system auditable?" → This table.
 */
@Entity
@Table(
    name = "investigation_results",
    indexes = {
        @Index(name = "idx_inv_results_incident_id", columnList = "incident_id"),
        @Index(name = "idx_inv_results_agent_type", columnList = "agent_type")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestigationResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false)
    private IncidentEntity incident;

    @Column(name = "agent_type", nullable = false, length = 100)
    private String agentType;  // "PLANNER", "LOG_METRICS", "KNOWLEDGE", "RCA_RECOMMENDATION"

    @Column(name = "findings", columnDefinition = "TEXT")
    private String findings;   // Structured findings from the agent

    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;  // Chain-of-thought from the LLM

    @Column(name = "confidence_score")
    private Float confidenceScore;  // 0.0 - 1.0

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;   // How long the agent took — for observability

    @Column(name = "model_used", length = 100)
    private String modelUsed;  // "gpt-4o", "llama3.2", etc. — track model versions

    @Column(name = "tokens_used")
    private Integer tokensUsed;   // LLM token consumption — for cost tracking

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
