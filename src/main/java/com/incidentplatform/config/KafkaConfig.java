package com.incidentplatform.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaConfig — Kafka topics and producer configuration.
 *
 * WHY define topics as Spring Beans?
 * If a topic doesn't exist when the producer tries to publish, Kafka throws an exception.
 * By defining topics as @Bean, Spring's KafkaAdmin creates them on startup automatically.
 * We also create topics in docker-compose kafka-init for reliability — defence in depth.
 * If BOTH exist, Kafka ignores the duplicate (idempotent topic creation).
 *
 * WHY TopicBuilder?
 * Spring Kafka's fluent API for topic creation. Cleaner than constructing NewTopic directly.
 * Sets partitions and replicas per topic based on our throughput requirements.
 *
 * PARTITION STRATEGY — Interview Gold:
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ Partitions = max parallelism for consumers in a consumer group  │
 * │ More partitions = more consumers can run in parallel            │
 * │ alert-events: 6 partitions → 6 consumers can read in parallel  │
 * │ Ordering: guaranteed WITHIN a partition, not across partitions  │
 * │ Key-based partitioning: same serviceName → same partition       │
 * │ → All alerts for "checkout-service" are ordered                 │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Interview: "How do you guarantee ordering in Kafka?"
 * → Partition by the key that needs ordering. All messages with same key
 *   go to same partition. Ordering is guaranteed per partition.
 *   We partition alerts by serviceName — all checkout alerts are ordered.
 */
@Configuration
public class KafkaConfig {

    // Topic name constants — single source of truth
    // WHY constants? If you change topic name in one place, compiler finds all usages.
    // Magic strings scattered across code = impossible to refactor safely.
    public static final String ALERT_EVENTS_TOPIC = "alert-events";
    public static final String INCIDENT_EVENTS_TOPIC = "incident-events";
    public static final String INVESTIGATION_EVENTS_TOPIC = "investigation-events";
    public static final String RECOMMENDATION_EVENTS_TOPIC = "recommendation-events";

    // Dead Letter Queue topics — suffix convention: original-topic + "-dlq"
    public static final String ALERT_EVENTS_DLQ = "alert-events-dlq";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * alert-events: 6 partitions
     * WHY 6? Supports up to 6 parallel consumer instances.
     * Alert ingestion is our highest-volume topic — needs most parallelism.
     * Rule: partitions ≥ peak consumer instances you'll ever run.
     * Can't reduce partitions later without data loss — plan ahead.
     */
    @Bean
    public NewTopic alertEventsTopic() {
        return TopicBuilder.name(ALERT_EVENTS_TOPIC)
                .partitions(6)
                .replicas(1)    // Dev: 1. Production: 3 (matches broker count)
                .build();
    }

    /**
     * incident-events: 3 partitions
     * Lower volume than alerts — one incident per correlated alert group.
     * Partitioned by incidentId to maintain ordering per incident.
     */
    @Bean
    public NewTopic incidentEventsTopic() {
        return TopicBuilder.name(INCIDENT_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic investigationEventsTopic() {
        return TopicBuilder.name(INVESTIGATION_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic recommendationEventsTopic() {
        return TopicBuilder.name(RECOMMENDATION_EVENTS_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Dead Letter Queue for alert-events
     *
     * WHY DLQ?
     * When a consumer fails to process a message after N retries, the message
     * goes to DLQ instead of blocking the partition forever.
     * Ops team can inspect DLQ, fix the root cause, then replay the messages.
     *
     * WITHOUT DLQ: one bad message blocks ALL messages in that partition.
     * This is called a "poison pill" — a message that kills your consumer.
     *
     * Interview: "What is a poison pill in Kafka and how do you handle it?"
     * → A message that always fails processing. DLQ routes it away so other
     *   messages can continue. DLQ has 1 partition for ordered inspection.
     */
    @Bean
    public NewTopic alertEventsDlqTopic() {
        return TopicBuilder.name(ALERT_EVENTS_DLQ)
                .partitions(1)    // DLQ: 1 partition, manual inspection, no parallelism needed
                .replicas(1)
                .build();
    }
}
