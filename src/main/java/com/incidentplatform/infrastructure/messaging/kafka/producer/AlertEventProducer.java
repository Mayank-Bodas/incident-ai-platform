package com.incidentplatform.infrastructure.messaging.kafka.producer;

import com.incidentplatform.application.dto.request.CreateAlertRequest;
import com.incidentplatform.config.KafkaConfig;
import com.incidentplatform.infrastructure.messaging.kafka.event.AlertCreatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * AlertEventProducer — Publishes alert events to Kafka.
 *
 * WHY @Component and not @Service?
 * @Service semantically means "contains business logic".
 * This class just publishes messages — it's infrastructure, not business logic.
 * @Component = generic Spring-managed bean, appropriate for infrastructure classes.
 * Both work, but semantics matter in large codebases.
 *
 * WHY KafkaTemplate<String, AlertCreatedEvent>?
 * KafkaTemplate is Spring Kafka's high-level producer abstraction.
 * Type params: <KEY_TYPE, VALUE_TYPE>
 * KEY = String (we use serviceName as the partition key)
 * VALUE = AlertCreatedEvent (serialized to JSON by JsonSerializer)
 *
 * WHY use serviceName as the Kafka message KEY?
 * Kafka routes messages with the same key to the same partition.
 * All alerts for "checkout-service" go to the same partition.
 * This guarantees ordering: alert1 for checkout is always processed before alert2.
 * Without key: messages distributed randomly across partitions → no ordering guarantee.
 *
 * Interview: "How do you maintain message ordering in Kafka?"
 * → Set a meaningful partition key. All messages with the same key land in the
 *   same partition, which is consumed in order by one consumer at a time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertEventProducer {

    private final KafkaTemplate<String, AlertCreatedEvent> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Publishes an AlertCreatedEvent to the alert-events topic asynchronously.
     *
     * WHY return void and not CompletableFuture<SendResult>?
     * The controller doesn't need to wait for Kafka acknowledgment.
     * We handle success/failure via callbacks (thenAccept / exceptionally).
     * The controller returns 202 Accepted IMMEDIATELY — true fire-and-forget.
     *
     * WHY use send() and not sendDefault()?
     * send() lets us specify topic, key, and value explicitly.
     * sendDefault() uses a default topic — less explicit, harder to reason about.
     * Explicit > implicit in production code.
     */
    public void publishAlertCreatedEvent(CreateAlertRequest request) {
        String correlationId = UUID.randomUUID().toString();

        AlertCreatedEvent event = AlertCreatedEvent.builder()
                .correlationId(correlationId)
                .alertTitle(request.title())
                .alertDescription(request.description())
                .severity(request.severity())
                .source(request.source())
                .serviceName(request.serviceName())
                .environment(request.environment() != null ? request.environment() : "production")
                .rawPayload(request.rawPayload())
                .firedAt(LocalDateTime.now())
                .producedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .producedBy("incident-platform")
                .build();

        // WHY send to topic with serviceName as key?
        // Key-based partitioning: all alerts for same service → same partition → ordered.
        CompletableFuture<SendResult<String, AlertCreatedEvent>> future =
                kafkaTemplate.send(KafkaConfig.ALERT_EVENTS_TOPIC, request.serviceName(), event);

        // WHY thenAccept / exceptionally callbacks?
        // Kafka send is async — we don't block waiting for broker acknowledgment.
        // Callbacks fire AFTER the broker confirms receipt (or fails).
        // This is how you get non-blocking + error visibility simultaneously.
        future.thenAccept(result -> {
            log.info("Alert event published successfully: correlationId={}, topic={}, partition={}, offset={}",
                    correlationId,
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset()
                    // WHY log partition + offset?
                    // If processing fails, you can seek to this exact offset for replay.
                    // Offset is the "address" of a message in Kafka. Log it = instant traceability.
            );
            meterRegistry.counter("kafka.alerts.published",
                    "service", request.serviceName(),
                    "severity", request.severity().name()
            ).increment();
        }).exceptionally(ex -> {
            // WHY log at ERROR?
            // Failed Kafka publish = alert lost. This MUST alert the ops team.
            // In production: also trigger a fallback (direct DB write, or dead letter).
            log.error("CRITICAL: Failed to publish alert event to Kafka: correlationId={}, service={}, error={}",
                    correlationId, request.serviceName(), ex.getMessage(), ex);
            meterRegistry.counter("kafka.alerts.publish.failed",
                    "service", request.serviceName()
            ).increment();
            return null;
        });

        log.info("Alert event submitted to Kafka: correlationId={}, service={}, severity={}",
                correlationId, request.serviceName(), request.severity());
    }
}
