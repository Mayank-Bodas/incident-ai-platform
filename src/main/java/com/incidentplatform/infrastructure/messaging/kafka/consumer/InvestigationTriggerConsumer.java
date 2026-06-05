package com.incidentplatform.infrastructure.messaging.kafka.consumer;

import com.incidentplatform.config.KafkaConfig;
import com.incidentplatform.application.agent.AgentOrchestrator;
import com.incidentplatform.infrastructure.messaging.kafka.event.IncidentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * InvestigationTriggerConsumer — Listens to the incident-events Kafka topic
 * and triggers the AI investigation orchestration pipeline.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class InvestigationTriggerConsumer {

    private final AgentOrchestrator agentOrchestrator;

    @KafkaListener(
            topics = KafkaConfig.INCIDENT_EVENTS_TOPIC,
            groupId = "investigation-trigger-group-${random.uuid}",
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
                │  Triggering AgentOrchestrator asynchronously...
                └─────────────────────────────────────────────────────────┘
                """,
                event.getIncidentId(),
                event.getServiceName(),
                event.getSeverity(),
                event.getCorrelationId());

        try {
            UUID incidentId = UUID.fromString(event.getIncidentId());
            agentOrchestrator.investigateIncident(incidentId);
        } catch (Exception e) {
            log.error("Failed to parse incidentId or launch AI investigation for event {}", event, e);
        }

        ack.acknowledge();
    }
}
