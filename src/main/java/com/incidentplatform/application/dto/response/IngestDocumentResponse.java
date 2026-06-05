package com.incidentplatform.application.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * IngestDocumentResponse — Response payload representing the ingested runbook.
 */
public record IngestDocumentResponse(
    UUID id,
    String title,
    String serviceName,
    List<String> tags,
    String documentType
) {}
