package com.incidentplatform.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * IngestDocumentRequest — Input validation DTO for runbook ingestion.
 */
public record IngestDocumentRequest(
    @NotBlank(message = "Document title is required")
    String title,

    @NotBlank(message = "Document content is required")
    String content,

    @NotBlank(message = "Associated service name is required")
    String serviceName,

    List<String> tags
) {}
