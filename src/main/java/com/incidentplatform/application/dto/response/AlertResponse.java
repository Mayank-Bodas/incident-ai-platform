package com.incidentplatform.application.dto.response;

import com.incidentplatform.domain.enums.Severity;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record AlertResponse(
    UUID id,
    UUID incidentId,
    String title,
    Severity severity,
    String source,
    String serviceName,
    String fingerprint,
    LocalDateTime createdAt
) {}
