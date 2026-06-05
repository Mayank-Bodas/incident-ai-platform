package com.incidentplatform.application.service;

import com.incidentplatform.application.dto.request.CreateAlertRequest;
import com.incidentplatform.application.dto.response.AlertResponse;
import com.incidentplatform.application.dto.response.IncidentResponse;
import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import com.incidentplatform.domain.exception.DuplicateAlertException;
import com.incidentplatform.domain.exception.IncidentNotFoundException;
import com.incidentplatform.domain.exception.InvalidStateTransitionException;
import com.incidentplatform.infrastructure.persistence.entity.AlertEntity;
import com.incidentplatform.infrastructure.persistence.entity.AuditLogEntity;
import com.incidentplatform.infrastructure.persistence.entity.IncidentEntity;
import com.incidentplatform.infrastructure.persistence.mapper.IncidentMapper;
import com.incidentplatform.infrastructure.persistence.repository.AlertRepository;
import com.incidentplatform.infrastructure.persistence.repository.AuditLogRepository;
import com.incidentplatform.infrastructure.persistence.repository.IncidentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.redis.core.RedisTemplate;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * IncidentService — Core business logic for alert ingestion and incident management.
 *
 * WHY @Service?
 * Marks this as a Spring-managed service bean. Also a semantic marker:
 * "This class contains business logic" (vs @Repository = data access, @Controller = HTTP).
 *
 * WHY @RequiredArgsConstructor (Lombok)?
 * Generates a constructor for all 'final' fields.
 * Spring uses constructor injection (not field injection with @Autowired).
 * WHY constructor injection over @Autowired on field?
 * 1. Makes dependencies explicit — visible in constructor
 * 2. Enables unit testing without Spring context (pass mocks directly)
 * 3. Immutability — final fields can't be reassigned
 * Interview: "What type of DI do you use?" → Constructor injection, because testability.
 *
 * WHY @Transactional?
 * Groups multiple DB operations into ONE atomic transaction.
 * If any step fails: ALL changes roll back. No partial data.
 * Example: create incident + create alert + create audit log — all or nothing.
 * Interview: "What is @Transactional?" → ACID transaction boundary. All-or-nothing.
 *
 * @Transactional(readOnly = true) on queries:
 * - Prevents accidental writes in read methods
 * - Hibernate skips dirty checking (performance optimization)
 * - Some DBs route readOnly to read replicas (horizontal scaling)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final AlertRepository alertRepository;
    private final AuditLogRepository auditLogRepository;
    private final IncidentMapper incidentMapper;
    private final MeterRegistry meterRegistry;
    private final RedisTemplate<String, Object> redisTemplate;

    // WHY inject MeterRegistry?
    // Micrometer metrics: count alerts received, track incident creation rate.
    // These metrics appear in /actuator/prometheus → Grafana dashboard.

    /**
     * Processes an incoming alert:
     * 1. Compute fingerprint for deduplication
     * 2. Check for duplicate in deduplication window
     * 3. Create or link to existing incident
     * 4. Create alert record
     * 5. Record audit log
     * 6. Emit Kafka event (added in Day 3)
     */
    public AlertResponse processAlert(CreateAlertRequest request) {
        log.info("Processing alert: service={}, severity={}, source={}",
                request.serviceName(), request.severity(), request.source());

        // Step 1: Generate fingerprint for deduplication
        String fingerprint = generateFingerprint(request.source(), request.serviceName(), request.title());

        // Step 2: Check deduplication window (10 minutes)
        LocalDateTime deduplicationWindow = LocalDateTime.now().minusMinutes(10);
        boolean isDuplicate = alertRepository
                .findByFingerprintAndCreatedAtAfter(fingerprint, deduplicationWindow)
                .isPresent();

        if (isDuplicate) {
            log.info("Duplicate alert detected, fingerprint={}. Skipping.", fingerprint);
            meterRegistry.counter("alerts.deduplicated").increment();
            throw new DuplicateAlertException(fingerprint);
        }

        // Step 3: Find or create incident
        // WHY "find or create" instead of always creating?
        // Multiple alerts for same service = same incident. Don't create noise.
        IncidentEntity incident = findOrCreateIncident(request);

        // Step 4: Create alert record
        AlertEntity alert = AlertEntity.builder()
                .incident(incident)
                .title(request.title())
                .description(request.description())
                .severity(request.severity())
                .source(request.source())
                .serviceName(request.serviceName())
                .environment(request.environment() != null ? request.environment() : "production")
                .fingerprint(fingerprint)
                .rawPayload(request.rawPayload())
                .firedAt(LocalDateTime.now())
                .build();

        AlertEntity savedAlert = alertRepository.save(alert);

        // Step 5: Audit log
        recordAuditLog("ALERT", savedAlert.getId().toString(),
                "CREATED", null, "source=" + request.source(), "SYSTEM");

        // Step 6: Metrics
        meterRegistry.counter("alerts.received",
                "severity", request.severity().name(),
                "source", request.source()
        ).increment();
        // WHY tags on metrics?
        // Allows Grafana to filter: "Show me only SEV1 alerts from prometheus"
        // Without tags: all alerts counted together, useless for debugging.

        log.info("Alert processed: alertId={}, incidentId={}", savedAlert.getId(), incident.getId());
        return incidentMapper.toAlertResponse(savedAlert);
    }

    private IncidentEntity findOrCreateIncident(CreateAlertRequest request) {
        // Check if there's an open/investigating incident for this service
        List<IncidentStatus> activeStatuses = List.of(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING);
        boolean hasActiveIncident = incidentRepository
                .existsByServiceNameAndStatusIn(request.serviceName(), activeStatuses);

        if (hasActiveIncident) {
            // Find the most recent active incident for this service
            return incidentRepository
                    .findByServiceName(request.serviceName(), Pageable.ofSize(1))
                    .stream()
                    .findFirst()
                    .orElseThrow();
        }

        // Create a new incident
        IncidentEntity newIncident = IncidentEntity.builder()
                .title("Incident: " + request.title())
                .description("Auto-created from alert: " + request.description())
                .status(IncidentStatus.OPEN)
                .severity(request.severity())
                .serviceName(request.serviceName())
                .environment(request.environment() != null ? request.environment() : "production")
                .createdBy("SYSTEM")
                .build();

        IncidentEntity savedIncident = incidentRepository.save(newIncident);

        recordAuditLog("INCIDENT", savedIncident.getId().toString(),
                "CREATED", null, "status=OPEN", "SYSTEM");

        meterRegistry.counter("incidents.created",
                "severity", request.severity().name()
        ).increment();

        log.info("New incident created: id={}, service={}", savedIncident.getId(), request.serviceName());
        return savedIncident;
    }

    /**
     * Transitions an incident to a new status.
     * Validates the transition using the state machine on IncidentEntity.
     *
     * WHY validate transition in service + entity?
     * Entity validates the rule (canTransitionTo). Service enforces it.
     * This is double validation: domain object knows the rules, service enforces them.
     */
    public IncidentResponse updateIncidentStatus(UUID incidentId, IncidentStatus newStatus, String updatedBy) {
        IncidentEntity incident = findIncidentOrThrow(incidentId);
        String oldStatus = incident.getStatus().name();

        if (!incident.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(incident.getStatus().name(), newStatus.name());
        }

        incident.setStatus(newStatus);

        // Set timestamp fields based on new status
        if (newStatus == IncidentStatus.RESOLVED) {
            incident.setResolvedAt(LocalDateTime.now());
        } else if (newStatus == IncidentStatus.CLOSED) {
            incident.setClosedAt(LocalDateTime.now());
        }

        IncidentEntity saved = incidentRepository.save(incident);

        // Evict from Redis Cache to maintain consistency
        evictIncidentCache(incidentId);

        recordAuditLog("INCIDENT", incidentId.toString(),
                "STATUS_CHANGED", "status=" + oldStatus, "status=" + newStatus, updatedBy);

        log.info("Incident {} transitioned: {} → {}", incidentId, oldStatus, newStatus);
        return incidentMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public IncidentResponse getIncident(UUID incidentId) {
        String cacheKey = getIncidentCacheKey(incidentId);
        try {
            IncidentResponse cached = (IncidentResponse) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("Redis Cache HIT for incident: {}", incidentId);
                return cached;
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve incident from Redis cache: {}", e.getMessage());
        }

        log.info("Redis Cache MISS for incident: {}", incidentId);
        IncidentResponse response = incidentMapper.toResponse(findIncidentOrThrow(incidentId));

        try {
            // Cache-Aside: write back to cache. Dynamic TTL: 24hr for resolved/closed, 1hr for active.
            long ttlHours = (response.status() == IncidentStatus.RESOLVED || response.status() == IncidentStatus.CLOSED) ? 24 : 1;
            redisTemplate.opsForValue().set(cacheKey, response, Duration.ofHours(ttlHours));
            log.info("Saved incident: {} to Redis cache with TTL {} hours", incidentId, ttlHours);
        } catch (Exception e) {
            log.warn("Failed to save incident to Redis cache: {}", e.getMessage());
        }

        return response;
    }

    private String getIncidentCacheKey(UUID incidentId) {
        return "incident:" + incidentId.toString();
    }

    private void evictIncidentCache(UUID incidentId) {
        try {
            String key = getIncidentCacheKey(incidentId);
            redisTemplate.delete(key);
            log.info("Evicted incident from cache: {}", incidentId);
        } catch (Exception e) {
            log.warn("Failed to evict incident from Redis cache: {}", e.getMessage());
        }
    }


    @Transactional(readOnly = true)
    public Page<IncidentResponse> listIncidents(IncidentStatus status, Pageable pageable) {
        Page<IncidentEntity> page = (status != null)
                ? incidentRepository.findByStatus(status, pageable)
                : incidentRepository.findAll(pageable);

        return page.map(incidentMapper::toResponse);
        // WHY Page<IncidentResponse>?
        // Pagination is MANDATORY for list APIs. Never return unbounded lists.
        // 1 million incidents → 1 million rows in memory → OOM crash.
        // Pageable: client specifies page number + size. Server enforces max size.
        // Interview: "How do you implement pagination in Spring Boot?" → Pageable + Page
    }

    private IncidentEntity findIncidentOrThrow(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId.toString()));
    }

    private void recordAuditLog(String entityType, String entityId, String action,
                                 String oldValue, String newValue, String performedBy) {
        AuditLogEntity log = AuditLogEntity.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .performedBy(performedBy)
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Generates a SHA-256 fingerprint for alert deduplication.
     *
     * WHY SHA-256?
     * Deterministic: same input always produces same hash.
     * Fixed length: 64 hex chars regardless of input size.
     * Collision-resistant: extremely unlikely two different alerts produce same hash.
     *
     * WHY not MD5?
     * MD5 has known collision vulnerabilities. SHA-256 is the security standard.
     * Even for non-security uses: SHA-256 is the habit to build.
     */
    private String generateFingerprint(String source, String serviceName, String alertTitle) {
        try {
            String raw = source + ":" + serviceName + ":" + alertTitle;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
            // HexFormat.of().formatHex() is Java 17+ API — converts byte[] to hex string
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist in every JVM. This exception is theoretical.
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
