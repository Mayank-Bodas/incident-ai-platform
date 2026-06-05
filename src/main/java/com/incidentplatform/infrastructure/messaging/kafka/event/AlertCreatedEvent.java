package com.incidentplatform.infrastructure.messaging.kafka.event;

import com.incidentplatform.domain.enums.Severity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AlertCreatedEvent — The Kafka message that flows through the alert-events topic.
 *
 * WHY a separate Event class and not reuse CreateAlertRequest (the DTO)?
 * DTO = API contract (what client sends over HTTP)
 * Event = messaging contract (what flows through Kafka)
 * They look similar now but will diverge:
 *   - Events get versioned independently (EventV1, EventV2)
 *   - Events carry extra metadata (correlationId, timestamp, producerService)
 *   - DTOs carry validation annotations — meaningless on a Kafka message
 * SOLID: each class has ONE reason to change.
 *
 * WHY @Data instead of record?
 * Kafka's JsonDeserializer needs a no-args constructor + setters to deserialize.
 * Java records are immutable with no setters — Jackson can't deserialize into them
 * without extra config. @Data gives us both, keeping the consumer simple.
 * Alternative: use @JsonCreator on record — advanced config, adds complexity.
 *
 * WHY correlationId?
 * Distributed tracing: the same correlationId threads through:
 *   HTTP request → Kafka event → DB record → log lines
 * Without it: impossible to trace one alert's journey across services.
 * This is how companies like Uber and Lyft debug production issues.
 * Interview: "How do you trace a request across microservices?" → correlationId + distributed tracing
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertCreatedEvent {

    private String correlationId;
    // Unique ID for this event — used for deduplication and tracing

    private String alertTitle;
    private String alertDescription;
    private Severity severity;
    private String source;       // "prometheus", "datadog", "custom"
    private String serviceName;
    private String environment;
    private String rawPayload;   // Original JSON from monitoring system
    private LocalDateTime firedAt;

    private String producedAt;   // ISO timestamp when this event was created
    private String producedBy;   // "alert-service" — which service produced this
}
