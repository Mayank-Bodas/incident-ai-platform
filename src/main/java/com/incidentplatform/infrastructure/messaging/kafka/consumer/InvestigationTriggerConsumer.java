package com.incidentplatform.infrastructure.messaging.kafka.consumer;

import com.incidentplatform.config.KafkaConfig;
import com.incidentplatform.infrastructure.messaging.kafka.event.IncidentCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * InvestigationTriggerConsumer — Day 5 placeholder.
 *
 * WHY create this now if it's a placeholder?
 * Verifies the incident-events topic works end-to-end today.
 * On Day 5, we replace the log statement with actual AI agent invocation.
 * The pipeline is fully wired — Day 5 just fills in the logic.
 *
 * This follows the STRANGLER FIG PATTERN:
 * Gradually replace placeholder implementations with real ones.
 * At no point does the system stop working or need major restructuring.
 *
 * On Day 5, this becomes:
 * → Start LogAnalysisAgent
 * → Start MetricsAnalysisAgent
 * → Start KnowledgeBaseAgent
 * → Orchestrate all agents in parallel
 * → Synthesize findings into RCA
 */
@Component
@Slf4j
public class InvestigationTriggerConsumer {

    @KafkaListener(
            topics = KafkaConfig.INCIDENT_EVENTS_TOPIC,
            groupId = "investigation-trigger-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleIncidentCreatedEvent(
            ConsumerRecord<String, IncidentCreatedEvent> record,
            Acknowledgment ack) {

        IncidentCreatedEvent event = record.value();

        log.info("""
                ┌─────────────────────────────────────────────────────────┐
                │  INVESTIGATION TRIGGER — Incident ready for AI analysis  │
                │  incidentId  : {}
                │  service     : {}
                │  severity    : {}
                │  correlationId: {}
                │  [Day 5] AI agents will start here
                └─────────────────────────────────────────────────────────┘
                """,
                event.getIncidentId(),
                event.getServiceName(),
                event.getSeverity(),
                event.getCorrelationId());

        // TODO Day 5: agentOrchestrator.startInvestigation(event.getIncidentId());

        ack.acknowledge();
    }
}
