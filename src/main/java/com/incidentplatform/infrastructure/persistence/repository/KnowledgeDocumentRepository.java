package com.incidentplatform.infrastructure.persistence.repository;

import com.incidentplatform.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * KnowledgeDocumentRepository — Persistence operations for runbooks and documentation.
 */
@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID> {
    List<KnowledgeDocumentEntity> findByServiceName(String serviceName);
}
