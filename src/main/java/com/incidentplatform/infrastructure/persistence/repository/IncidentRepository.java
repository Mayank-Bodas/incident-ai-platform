package com.incidentplatform.infrastructure.persistence.repository;

import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import com.incidentplatform.infrastructure.persistence.entity.IncidentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * IncidentRepository — Data access layer for incidents.
 *
 * WHY JpaRepository over CrudRepository?
 * JpaRepository extends CrudRepository + PagingAndSortingRepository.
 * It adds: findAll(Pageable), saveAll(), flush(), deleteAllInBatch() etc.
 * Always use JpaRepository for production code.
 *
 * WHY JpaSpecificationExecutor?
 * Enables dynamic query building with Criteria API.
 * For complex filters: filter by status AND severity AND date range AND service.
 * Without Specification: you'd need 20+ method variants or @Query for each combination.
 * With Specification: buildable, composable, type-safe queries.
 * Interview: "How do you handle dynamic queries in Spring Data JPA?" → Specifications
 *
 * Method naming convention (Spring Data JPA magic):
 * findBy[Field][Condition] → Spring auto-generates the SQL query
 * No SQL needed! "findByStatus" → "SELECT * FROM incidents WHERE status = ?"
 *
 * Interview: "What is Spring Data JPA query derivation?"
 * → Spring parses method names and generates JPQL automatically at startup.
 */
@Repository
public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID>,
        JpaSpecificationExecutor<IncidentEntity> {

    // Derived query: SELECT * FROM incidents WHERE status = ?
    Page<IncidentEntity> findByStatus(IncidentStatus status, Pageable pageable);

    // Derived query with multiple conditions
    Page<IncidentEntity> findByStatusAndSeverity(IncidentStatus status, Severity severity, Pageable pageable);

    // Custom JPQL query — for complex logic Spring can't derive
    @Query("SELECT i FROM IncidentEntity i WHERE i.status IN :statuses AND i.createdAt >= :since")
    List<IncidentEntity> findActiveIncidentsSince(
            @Param("statuses") List<IncidentStatus> statuses,
            @Param("since") LocalDateTime since
    );

    // Count by status — used for dashboard metrics
    long countByStatus(IncidentStatus status);

    // Check for duplicate incident (deduplication)
    boolean existsByServiceNameAndStatusIn(String serviceName, List<IncidentStatus> statuses);

    // Find by service name with pagination
    Page<IncidentEntity> findByServiceName(String serviceName, Pageable pageable);

    @Query("""
        SELECT i FROM IncidentEntity i
        WHERE i.severity = :severity
        AND i.status NOT IN ('RESOLVED', 'CLOSED')
        ORDER BY i.createdAt DESC
    """)
    // WHY Text Blocks (triple quotes)?
    // Java 15+ feature. Multi-line strings without concatenation.
    // Much more readable for long JPQL/SQL queries.
    List<IncidentEntity> findOpenIncidentsBySeverity(@Param("severity") Severity severity);
}
