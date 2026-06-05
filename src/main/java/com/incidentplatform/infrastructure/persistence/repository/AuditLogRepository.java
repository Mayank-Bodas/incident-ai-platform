package com.incidentplatform.infrastructure.persistence.repository;

import com.incidentplatform.infrastructure.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
    Page<AuditLogEntity> findByEntityIdOrderByCreatedAtDesc(String entityId, Pageable pageable);
    Page<AuditLogEntity> findByPerformedByOrderByCreatedAtDesc(String performedBy, Pageable pageable);
}
