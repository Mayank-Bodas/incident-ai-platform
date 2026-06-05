package com.incidentplatform.infrastructure.persistence.mapper;

import com.incidentplatform.application.dto.response.AlertResponse;
import com.incidentplatform.application.dto.response.IncidentResponse;
import com.incidentplatform.infrastructure.persistence.entity.AlertEntity;
import com.incidentplatform.infrastructure.persistence.entity.IncidentEntity;
import org.springframework.stereotype.Component;

/**
 * IncidentMapper — Converts between Entity and DTO.
 *
 * WHY a manual mapper instead of MapStruct?
 * MapStruct generates compile-time mapping code (fast, type-safe).
 * For learning: manual mapper shows exactly what fields map to what.
 * In a real team with 50+ DTOs: use MapStruct to eliminate boilerplate.
 *
 * WHY not put this logic in the service?
 * Single Responsibility: service = business logic. Mapper = data transformation.
 * Easier to test mappers independently. Cleaner service code.
 *
 * Alternative: record compact constructors, but mappers scale better.
 */
@Component
public class IncidentMapper {

    public IncidentResponse toResponse(IncidentEntity entity) {
        return IncidentResponse.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .severity(entity.getSeverity())
                .serviceName(entity.getServiceName())
                .environment(entity.getEnvironment())
                .rcaSummary(entity.getRcaSummary())
                .resolutionNotes(entity.getResolutionNotes())
                .resolvedAt(entity.getResolvedAt())
                .closedAt(entity.getClosedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .build();
    }

    public AlertResponse toAlertResponse(AlertEntity entity) {
        return AlertResponse.builder()
                .id(entity.getId())
                .incidentId(entity.getIncident().getId())
                .title(entity.getTitle())
                .severity(entity.getSeverity())
                .source(entity.getSource())
                .serviceName(entity.getServiceName())
                .fingerprint(entity.getFingerprint())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public com.incidentplatform.application.dto.response.InvestigationResultResponse toInvestigationResponse(com.incidentplatform.infrastructure.persistence.entity.InvestigationResultEntity entity) {
        return com.incidentplatform.application.dto.response.InvestigationResultResponse.builder()
                .id(entity.getId())
                .agentType(entity.getAgentType())
                .findings(entity.getFindings())
                .reasoning(entity.getReasoning())
                .confidenceScore(entity.getConfidenceScore())
                .executionTimeMs(entity.getExecutionTimeMs())
                .modelUsed(entity.getModelUsed())
                .tokensUsed(entity.getTokensUsed())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
