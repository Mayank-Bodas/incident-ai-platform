package com.incidentplatform.interfaces.rest.controller;

import com.incidentplatform.infrastructure.search.document.IncidentDocument;
import com.incidentplatform.infrastructure.search.document.LogDocument;
import com.incidentplatform.infrastructure.search.repository.IncidentElasticsearchRepository;
import com.incidentplatform.infrastructure.search.repository.LogElasticsearchRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SearchController — REST API exposing full-text search across indexed incidents and logs.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Full-Text Search", description = "Endpoints for searching incidents and logs indexed in Elasticsearch")
public class SearchController {

    private final IncidentElasticsearchRepository incidentElasticsearchRepository;
    private final LogElasticsearchRepository logElasticsearchRepository;

    /**
     * GET /api/v1/search/incidents — Search incidents by query string.
     */
    @GetMapping("/incidents")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENGINEER', 'VIEWER')")
    @Operation(summary = "Search incidents", description = "Performs standard full-text search across incident title, description, and rca_summary fields.")
    public ResponseEntity<List<IncidentDocument>> searchIncidents(@RequestParam String query) {
        log.info("Received request to search incidents with query: '{}'", query);
        List<IncidentDocument> results = incidentElasticsearchRepository.search(query);
        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/v1/search/logs — Search logs by service name and optional text query.
     */
    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENGINEER', 'VIEWER')")
    @Operation(summary = "Search service logs", description = "Search logs for a specific service, optionally matching a message text query.")
    public ResponseEntity<List<LogDocument>> searchLogs(
            @RequestParam String serviceName,
            @RequestParam(required = false) String query) {
        
        log.info("Received request to search logs for service: '{}', query: '{}'", serviceName, query);
        
        List<LogDocument> results;
        if (query == null || query.trim().isEmpty()) {
            // Default: return logs from last 15 minutes
            LocalDateTime fifteenMinutesAgo = LocalDateTime.now().minusMinutes(15);
            results = logElasticsearchRepository.findByServiceNameAndTimestampAfter(serviceName, fifteenMinutesAgo);
        } else {
            // Search logs matching query text
            results = logElasticsearchRepository.searchLogs(serviceName, query);
        }
        
        return ResponseEntity.ok(results);
    }
}
