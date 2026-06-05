package com.incidentplatform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * KnowledgeDocumentEntity — Represents runbooks, SOPs, and troubleshooting guides in PostgreSQL.
 * Used by the Knowledge Base Agent for context retrieval (RAG foundation).
 */
@Entity
@Table(
    name = "knowledge_documents",
    indexes = {
        @Index(name = "idx_knowledge_docs_service_name", columnList = "service_name")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "document_type", nullable = false, length = 100)
    @Builder.Default
    private String documentType = "RUNBOOK";

    @Column(name = "service_name", length = 255)
    private String serviceName;

    // Hibernate 6 maps Java List<String> directly to PostgreSQL text[] array
    @Column(name = "tags", columnDefinition = "text[]")
    private List<String> tags;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
