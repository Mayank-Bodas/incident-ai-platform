package com.incidentplatform.application.dto.request;

import com.incidentplatform.domain.enums.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * CreateAlertRequest — DTO for incoming alert payloads.
 *
 * WHY a separate DTO and not use the entity directly?
 * 1. Security: Entity exposes ALL fields. DTO exposes only what client should send.
 *    If you accept an entity directly, a malicious client could set id, createdAt etc.
 * 2. Versioning: API can change independently of the DB schema.
 * 3. Validation: Validation annotations belong on the DTO, not the domain/entity.
 * 4. Decoupling: API contract is stable even if DB schema changes.
 * This is the DTO pattern — fundamental to REST API design.
 * Interview: "Why use DTOs?" → This exact explanation.
 *
 * WHY Java record?
 * Java 16+ feature. Immutable data carrier. Auto-generates:
 *   - Constructor for all fields
 *   - getters (field names, not getXxx)
 *   - equals(), hashCode(), toString()
 * Perfect for DTOs — immutable, concise, no Lombok needed.
 * BUT: records don't work well with Jackson's @Valid on some versions.
 * We use @Builder from Lombok for flexibility.
 *
 * WHY @NotBlank vs @NotNull?
 * @NotNull: field must not be null (but "" is valid)
 * @NotBlank: field must not be null, empty, or whitespace-only
 * For string fields: always prefer @NotBlank.
 */
@Builder
public record CreateAlertRequest(

    @NotBlank(message = "Alert title is required")
    @Size(max = 500, message = "Title must not exceed 500 characters")
    String title,

    String description,

    @NotNull(message = "Severity is required")
    Severity severity,

    @NotBlank(message = "Source system is required")
    @Size(max = 100, message = "Source must not exceed 100 characters")
    String source,

    @NotBlank(message = "Service name is required")
    String serviceName,

    String environment,

    String rawPayload
    // rawPayload = the original JSON from monitoring system.
    // We store it verbatim for audit purposes.
) {}
