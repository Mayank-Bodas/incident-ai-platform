package com.incidentplatform.application.dto.response;

import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * IncidentResponse — DTO returned to API clients.
 *
 * WHY not return the IncidentEntity directly?
 * 1. Entities have lazy-loaded collections (alerts, results) that Jackson
 *    tries to serialize → LazyInitializationException (VERY common Spring bug)
 * 2. Entities expose internal DB fields (passwordHash, updatedAt) clients don't need
 * 3. Response format can evolve independently of DB schema
 *
 * WHY @Builder on record?
 * Records have a canonical constructor, but @Builder lets us:
 * IncidentResponse.builder().id(uuid).title("DB down").build()
 * More readable than IncidentResponse(uuid, "DB down", null, null, ...) with 12 args.
 *
 * Production practice: Use separate Response DTOs, never expose entities.
 * This is also why you see "ApiResponse", "IncidentDto", "IncidentView" naming patterns.
 */
@Builder
public record IncidentResponse(
    UUID id,
    String title,
    String description,
    IncidentStatus status,
    Severity severity,
    String serviceName,
    String environment,
    String rcaSummary,
    String resolutionNotes,
    LocalDateTime resolvedAt,
    LocalDateTime closedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String createdBy
) {}
