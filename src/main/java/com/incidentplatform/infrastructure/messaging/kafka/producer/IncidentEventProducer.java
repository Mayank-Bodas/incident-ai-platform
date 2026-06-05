package com.incidentplatform.infrastructure.messaging.kafka.producer;

import com.incidentplatform.config.KafkaConfig;
import com.incidentplatform.infrastructure.messaging.kafka.event.IncidentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * IncidentEventProducer — Publishes incident lifecycle events.
 *
 * Published AFTER an incident is created by IncidentCoordinator.
 * Consumed by InvestigationTriggerConsumer (Day 5: AI agents start here).
 *
 * This is the second event in our event chain:
 * AlertCreatedEvent → [alert-events] → IncidentCreatedEvent → [incident-events] → Investigation
 *
 * WHY chain events instead of calling investigation directly?
 * 1. Decoupling: IncidentCoordinator doesn't know InvestigationService exists
 * 2. Replayability: re-trigger investigation by replaying incident-events
 * 3. Future consumers: notification service, SLA tracker, etc. can all consume incident-events
 *    without changing IncidentCoordinator at all (Open/Closed Principle)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IncidentEventProducer {

    private final KafkaTemplate<String, IncidentCreatedEvent> kafkaTemplate;

    public void publishIncidentCreatedEvent(IncidentCreatedEvent event) {
        // WHY incidentId as key?
        // All events for the same incident go to the same partition.
        // This ensures ordered processing per incident:
        // CREATED → INVESTIGATING → RESOLVED are processed in order.
        kafkaTemplate.send(KafkaConfig.INCIDENT_EVENTS_TOPIC, event.getIncidentId(), event)
                .thenAccept(result ->
                        log.info("Incident event published: incidentId={}, partition={}, offset={}",
                                event.getIncidentId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset()))
                .exceptionally(ex -> {
                    log.error("Failed to publish incident event: incidentId={}, error={}",
                            event.getIncidentId(), ex.getMessage(), ex);
                    return null;
                });
    }
}
