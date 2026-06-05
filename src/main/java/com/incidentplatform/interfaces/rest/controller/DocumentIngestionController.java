package com.incidentplatform.interfaces.rest.controller;

import com.incidentplatform.application.dto.request.IngestDocumentRequest;
import com.incidentplatform.application.dto.response.IngestDocumentResponse;
import com.incidentplatform.application.service.DocumentIngestionService;
import com.incidentplatform.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * DocumentIngestionController — REST API for uploading runbooks and SRE documents.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Runbook Ingestion & RAG", description = "Endpoints for ingesting SRE runbooks, split/chunk them, and index in pgvector")
public class DocumentIngestionController {

    private final DocumentIngestionService documentIngestionService;

    /**
     * POST /api/v1/documents/ingest — Upload and index a runbook.
     * Accessible by ADMIN and ENGINEER roles.
     */
    @PostMapping("/documents/ingest")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENGINEER')")
    @Operation(summary = "Ingest a runbook document", description = "Saves runbook content to database, chunks it, generates vector embeddings, and indexes in pgvector.")
    public ResponseEntity<IngestDocumentResponse> ingestDocument(
            @Valid @RequestBody IngestDocumentRequest request,
            Principal principal) {

        String username = principal != null ? principal.getName() : "SYSTEM";
        log.info("Ingestion request received from user '{}' for document '{}'", username, request.title());

        KnowledgeDocumentEntity doc = documentIngestionService.ingestDocument(
                request.title(),
                request.content(),
                request.serviceName(),
                request.tags(),
                username
        );

        IngestDocumentResponse response = new IngestDocumentResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getServiceName(),
                doc.getTags(),
                doc.getDocumentType()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
