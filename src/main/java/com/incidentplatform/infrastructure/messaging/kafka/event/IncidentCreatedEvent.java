package com.incidentplatform.infrastructure.messaging.kafka.event;

import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * IncidentCreatedEvent — Published to incident-events topic after an incident is created.
 *
 * WHY publish this event?
 * This triggers the AI investigation pipeline (Day 5).
 * The InvestigationTriggerConsumer listens for this and starts the agent orchestration.
 *
 * This is the EVENT-DRIVEN ARCHITECTURE pattern:
 * - Incident service says "incident was created" (publishes event)
 * - Investigation service says "I'll handle investigation" (subscribes to event)
 * - Neither service knows about the other — they only know about the event
 *
 * This decoupling means:
 *   - Investigation service can be restarted without affecting alert ingestion
 *   - Multiple consumers can react to the same event (future: notification service)
 *   - Kafka stores the event — replay all incidents through a new agent version
 *
 * Interview: "What is the difference between event-driven and request-driven?"
 * → Request-driven: ServiceA calls ServiceB directly (tight coupling)
 * → Event-driven: ServiceA publishes event, ServiceB subscribes (loose coupling)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentCreatedEvent {

    private String correlationId;    // Same correlationId from the original AlertCreatedEvent
    private String incidentId;       // UUID of the created incident
    private String alertId;          // UUID of the triggering alert
    private String title;
    private Severity severity;
    private IncidentStatus status;
    private String serviceName;
    private String environment;
    private LocalDateTime createdAt;
    private String triggeredBy;      // "ALERT_INGESTION" — why was this incident created
}
