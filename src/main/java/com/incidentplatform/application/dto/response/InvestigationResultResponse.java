package com.incidentplatform.application.dto.response;

import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record InvestigationResultResponse(
    UUID id,
    String agentType,
    String findings,
    String reasoning,
    BigDecimal confidenceScore,
    Long executionTimeMs,
    String modelUsed,
    Integer tokensUsed,
    LocalDateTime createdAt
) {}
