package com.incidentplatform.infrastructure.persistence.repository;

import com.incidentplatform.infrastructure.persistence.entity.InvestigationResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * InvestigationResultRepository — Persistence operations for AI agent findings.
 */
@Repository
public interface InvestigationResultRepository extends JpaRepository<InvestigationResultEntity, UUID> {
    List<InvestigationResultEntity> findByIncidentId(UUID incidentId);
}
