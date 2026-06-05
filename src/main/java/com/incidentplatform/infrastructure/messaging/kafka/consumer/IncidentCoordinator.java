package com.incidentplatform.infrastructure.messaging.kafka.consumer;

import com.incidentplatform.config.KafkaConfig;
import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import com.incidentplatform.domain.exception.DuplicateAlertException;
import com.incidentplatform.infrastructure.messaging.kafka.event.AlertCreatedEvent;
import com.incidentplatform.infrastructure.messaging.kafka.event.IncidentCreatedEvent;
import com.incidentplatform.infrastructure.messaging.kafka.producer.IncidentEventProducer;
import com.incidentplatform.infrastructure.persistence.entity.AlertEntity;
import com.incidentplatform.infrastructure.persistence.entity.AuditLogEntity;
import com.incidentplatform.infrastructure.persistence.entity.IncidentEntity;
import com.incidentplatform.infrastructure.persistence.repository.AlertRepository;
import com.incidentplatform.infrastructure.persistence.repository.AuditLogRepository;
import com.incidentplatform.infrastructure.persistence.repository.IncidentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * IncidentCoordinator — The brain of the async alert pipeline.
 *
 * WHY is this a separate consumer class and not in IncidentService?
 * Single Responsibility Principle:
 *   - IncidentService = business logic (can be called from anywhere)
 *   - IncidentCoordinator = Kafka-specific concerns (offset management, retry, DLQ)
 * This lets us test business logic without Kafka setup.
 *
 * @KafkaListener deep dive:
 *   topics = which topic to consume from
 *   groupId = consumer group name
 *   → Multiple instances with SAME groupId share the load (each partition → one consumer)
 *   → Multiple instances with DIFFERENT groupId each get ALL messages (fan-out)
 *   containerFactory = which listener factory to use (configured in application.yml)
 *
 * WHY @RetryableTopic?
 * Non-blocking retry pattern:
 * 1. Consumer fails to process message
 * 2. Message goes to a retry topic (alert-events-retry-1000) after 1 second
 * 3. After 3 retries, message goes to alert-events-dlq
 * WHY non-blocking? Traditional @Retryable blocks the thread during backoff.
 * With @RetryableTopic, the consumer moves on to other messages during backoff.
 * This is the MODERN way to handle retries in Spring Kafka.
 *
 * Interview: "How do you handle failures in Kafka consumers?"
 * → @RetryableTopic for non-blocking retry with exponential backoff,
 *   DLQ for poison pill messages, manual Acknowledgment for at-least-once delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IncidentCoordinator {

    private final IncidentRepository incidentRepository;
    private final AlertRepository alertRepository;
    private final AuditLogRepository auditLogRepository;
    private final IncidentEventProducer incidentEventProducer;
    private final MeterRegistry meterRegistry;

    /**
     * Consumes AlertCreatedEvent from alert-events topic.
     *
     * @RetryableTopic configuration:
     *   attempts = 4 → 1 original + 3 retries
     *   backoff = 1s, 3s, 9s (exponential: multiplier=3)
     *   dltTopicSuffix = "-dlq" → failed messages go to alert-events-dlq
     *   autoCreateTopics = false → we create topics manually in KafkaConfig
     *
     * WHY ConsumerRecord<String, AlertCreatedEvent> and not just AlertCreatedEvent?
     * ConsumerRecord gives us access to metadata:
     *   - record.key() = partition key (serviceName)
     *   - record.partition() = which partition this came from
     *   - record.offset() = exact position in the log
     * We need these for logging and debugging. The event alone doesn't have them.
     *
     * WHY Acknowledgment ack parameter?
     * Manual commit mode (set in application.yml: ack-mode: MANUAL_IMMEDIATE)
     * We call ack.acknowledge() ONLY after successful processing.
     * If processing fails: no ack → Kafka replays from the same offset.
     * If we acknowledged before processing and then crashed: message LOST.
     * At-least-once delivery: prefer duplicates over data loss.
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 3, maxDelay = 10000),
            dltTopicSuffix = "-dlq",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "true"
    )
    @KafkaListener(
            topics = KafkaConfig.ALERT_EVENTS_TOPIC,
            groupId = "incident-coordinator-group-${random.uuid}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleAlertCreatedEvent(
            ConsumerRecord<String, AlertCreatedEvent> record,
            Acknowledgment ack) {

        AlertCreatedEvent event = record.value();

        log.info("Received alert event: correlationId={}, service={}, severity={}, partition={}, offset={}",
                event.getCorrelationId(),
                event.getServiceName(),
                event.getSeverity(),
                record.partition(),
                record.offset());

        try {
            // Step 1: Compute fingerprint for deduplication
            String fingerprint = generateFingerprint(
                    event.getSource(), event.getServiceName(), event.getAlertTitle());

            // Step 2: Idempotency check — has this exact alert been processed recently?
            // WHY check here AND at the producer?
            // Producer checks: prevents publishing duplicate events to Kafka
            // Consumer checks: handles the case where same event was published multiple times
            //                  (e.g., producer retried after network issue → duplicate events)
            // This is the "idempotent consumer" pattern — safe to process same message twice.
            LocalDateTime deduplicationWindow = LocalDateTime.now().minusMinutes(10);
            boolean isDuplicate = alertRepository
                    .existsByFingerprintAndCreatedAtAfter(fingerprint, deduplicationWindow);

            if (isDuplicate) {
                log.info("Duplicate alert detected in consumer (fingerprint={}), skipping. correlationId={}",
                        fingerprint, event.getCorrelationId());
                meterRegistry.counter("kafka.alerts.deduplicated").increment();
                ack.acknowledge(); // Acknowledge duplicates — not a failure, don't retry
                return;
            }

            // Step 3: Find or create incident
            IncidentEntity incident = findOrCreateIncident(event);

            // Step 4: Create alert record
            AlertEntity alert = AlertEntity.builder()
                    .incident(incident)
                    .title(event.getAlertTitle())
                    .description(event.getAlertDescription())
                    .severity(event.getSeverity())
                    .source(event.getSource())
                    .serviceName(event.getServiceName())
                    .environment(event.getEnvironment())
                    .fingerprint(fingerprint)
                    .rawPayload(event.getRawPayload())
                    .firedAt(event.getFiredAt())
                    .build();

            AlertEntity savedAlert = alertRepository.save(alert);

            // Step 5: Write audit log
            auditLogRepository.save(AuditLogEntity.builder()
                    .entityType("ALERT")
                    .entityId(savedAlert.getId().toString())
                    .action("CREATED_FROM_KAFKA")
                    .newValue("correlationId=" + event.getCorrelationId()
                            + ", source=" + event.getSource())
                    .performedBy("SYSTEM")
                    .createdAt(LocalDateTime.now())
                    .build());

            // Step 6: Publish IncidentCreatedEvent → triggers investigation pipeline (Day 5)
            IncidentCreatedEvent incidentEvent = IncidentCreatedEvent.builder()
                    .correlationId(event.getCorrelationId())
                    .incidentId(incident.getId().toString())
                    .alertId(savedAlert.getId().toString())
                    .title(incident.getTitle())
                    .severity(incident.getSeverity())
                    .status(incident.getStatus())
                    .serviceName(incident.getServiceName())
                    .environment(incident.getEnvironment())
                    .createdAt(incident.getCreatedAt())
                    .triggeredBy("ALERT_INGESTION")
                    .build();

            incidentEventProducer.publishIncidentCreatedEvent(incidentEvent);

            // Step 7: Update metrics
            meterRegistry.counter("incidents.processed",
                    "severity", event.getSeverity().name(),
                    "service", event.getServiceName()
            ).increment();

            log.info("Alert processed successfully: alertId={}, incidentId={}, correlationId={}",
                    savedAlert.getId(), incident.getId(), event.getCorrelationId());

            // Step 8: Acknowledge AFTER successful processing
            // WHY acknowledge last?
            // If any step above throws an exception:
            // - @Transactional rolls back DB changes
            // - No ack → Kafka replays the message
            // - @RetryableTopic handles the retry with backoff
            ack.acknowledge();

        } catch (Exception ex) {
            log.error("Failed to process alert event: correlationId={}, service={}, error={}",
                    event.getCorrelationId(), event.getServiceName(), ex.getMessage(), ex);
            meterRegistry.counter("kafka.alerts.processing.failed",
                    "service", event.getServiceName()
            ).increment();
            // Don't ack → Kafka will retry via @RetryableTopic
            throw ex; // Re-throw so @RetryableTopic can route to retry/DLQ
        }
    }

    private IncidentEntity findOrCreateIncident(AlertCreatedEvent event) {
        // Check if there's an active incident for this service
        List<IncidentStatus> activeStatuses = List.of(
                IncidentStatus.OPEN, IncidentStatus.INVESTIGATING);

        boolean hasActive = incidentRepository
                .existsByServiceNameAndStatusIn(event.getServiceName(), activeStatuses);

        if (hasActive) {
            // Link this alert to the existing active incident
            return incidentRepository
                    .findByServiceName(event.getServiceName(),
                            org.springframework.data.domain.Pageable.ofSize(1))
                    .stream()
                    .findFirst()
                    .orElseThrow();
        }

        // Create new incident
        IncidentEntity newIncident = IncidentEntity.builder()
                .title("Incident: " + event.getAlertTitle())
                .description("Auto-created from alert via Kafka. Source: " + event.getSource())
                .status(IncidentStatus.OPEN)
                .severity(event.getSeverity())
                .serviceName(event.getServiceName())
                .environment(event.getEnvironment())
                .createdBy("SYSTEM")
                .build();

        IncidentEntity saved = incidentRepository.save(newIncident);

        auditLogRepository.save(AuditLogEntity.builder()
                .entityType("INCIDENT")
                .entityId(saved.getId().toString())
                .action("CREATED")
                .newValue("status=OPEN, triggeredBy=KAFKA_ALERT")
                .performedBy("SYSTEM")
                .createdAt(LocalDateTime.now())
                .build());

        log.info("New incident created: id={}, service={}, severity={}",
                saved.getId(), event.getServiceName(), event.getSeverity());

        meterRegistry.counter("incidents.created",
                "severity", event.getSeverity().name()
        ).increment();

        return saved;
    }

    private String generateFingerprint(String source, String serviceName, String alertTitle) {
        try {
            String raw = source + ":" + serviceName + ":" + alertTitle;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
