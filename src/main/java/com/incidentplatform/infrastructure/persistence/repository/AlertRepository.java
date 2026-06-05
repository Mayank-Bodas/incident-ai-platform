package com.incidentplatform.infrastructure.persistence.repository;

import com.incidentplatform.infrastructure.persistence.entity.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlertRepository extends JpaRepository<AlertEntity, UUID> {

    // WHY this query?
    // Alert deduplication: if same fingerprint seen in last 10 minutes, skip incident creation.
    // fingerprint = SHA-256(source + service + alertType)
    // createdAt > NOW() - 10 minutes = within deduplication window
    boolean existsByFingerprintAndCreatedAtAfter(String fingerprint, LocalDateTime after);

    long countByIncidentId(UUID incidentId);
}
