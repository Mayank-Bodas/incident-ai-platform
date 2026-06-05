package com.incidentplatform.interfaces.rest.controller;

import com.incidentplatform.application.dto.request.CreateAlertRequest;
import com.incidentplatform.application.dto.response.IncidentResponse;
import com.incidentplatform.application.dto.response.InvestigationResultResponse;
import com.incidentplatform.application.service.IncidentService;
import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.infrastructure.messaging.kafka.producer.AlertEventProducer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AlertController — REST API for alert ingestion.
 *
 * WHY @RestController?
 * = @Controller + @ResponseBody on every method.
 * Return values are serialized to JSON automatically. No @ResponseBody needed per method.
 *
 * WHY /api/v1 prefix?
 * API versioning from day one. When you need breaking changes:
 * - /api/v2 routes to new controller
 * - /api/v1 still works for old clients
 * Without versioning: breaking changes break ALL clients simultaneously.
 * Real companies: Netflix, Stripe, Twilio all version their APIs.
 * Interview: "How do you handle API versioning?" → URI versioning (/v1) or Header versioning
 *
 * WHY ResponseEntity<T>?
 * Fine-grained HTTP response control: status code, headers, body.
 * return ResponseEntity.status(201).body(response) — explicit HTTP 201 Created.
 * If you return T directly, Spring defaults to 200 OK.
 * 201 for create operations is semantically correct (REST best practice).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Alert & Incident Management", description = "APIs for alert ingestion and incident lifecycle management")
public class AlertController {

    private final IncidentService incidentService;
    private final AlertEventProducer alertEventProducer;

    /**
     * POST /api/v1/alerts — Receive an alert and publish to Kafka.
     *
     * WHY return 202 Accepted and not 201 Created?
     * 202 = "I received your request and will process it asynchronously"
     * 201 = "I created the resource right now"
     * Since alert → Kafka → incident creation happens async, 202 is semantically correct.
     * Client gets an instant response without waiting for DB operations.
     *
     * This is the key benefit of event-driven architecture:
     * No matter how long processing takes (DB slow, Kafka lag), the API response is instant.
     *
     * WHY return a Map with correlationId?
     * Client needs a way to track this alert's processing.
     * They can use correlationId to query logs, search incidents, or poll for status.
     * This is the ACCEPTED REQUEST pattern in async APIs.
     */
    @PostMapping("/alerts")
    @Operation(summary = "Ingest an alert",
            description = "Validates the alert and publishes to Kafka. Incident creation is async. Returns 202 Accepted immediately.")
    public ResponseEntity<Map<String, String>> ingestAlert(
            @Valid @RequestBody CreateAlertRequest request) {

        alertEventProducer.publishAlertCreatedEvent(request);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)  // 202 — processing will happen async
                .body(Map.of(
                        "status", "ACCEPTED",
                        "message", "Alert received and queued for processing",
                        "service", request.serviceName(),
                        "severity", request.severity().name()
                ));
    }

    /**
     * GET /api/v1/incidents — List incidents with pagination and optional status filter.
     *
     * WHY Pageable as a parameter?
     * Spring Data Web Support auto-populates Pageable from request params:
     * GET /api/v1/incidents?page=0&size=20&sort=createdAt,desc
     * @PageableDefault sets defaults when client doesn't specify.
     * Interview: "How does Spring resolve Pageable from HTTP request?" → PageableHandlerMethodArgumentResolver
     */
    @GetMapping("/incidents")
    @Operation(summary = "List incidents", description = "Get paginated list of incidents, optionally filtered by status")
    public ResponseEntity<Page<IncidentResponse>> listIncidents(
            @RequestParam(required = false) IncidentStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        return ResponseEntity.ok(incidentService.listIncidents(status, pageable));
    }

    /**
     * GET /api/v1/incidents/{id} — Get a specific incident by ID.
     */
    @GetMapping("/incidents/{id}")
    @Operation(summary = "Get incident by ID")
    public ResponseEntity<IncidentResponse> getIncident(@PathVariable UUID id) {
        return ResponseEntity.ok(incidentService.getIncident(id));
    }

    /**
     * PATCH /api/v1/incidents/{id}/status — Update incident status.
     *
     * WHY PATCH and not PUT?
     * PUT = replace entire resource. PATCH = partial update.
     * We're only changing the status field → PATCH is semantically correct.
     * Interview: "Difference between PUT and PATCH?" → PUT = full replace, PATCH = partial update
     */
    @PatchMapping("/incidents/{id}/status")
    @Operation(summary = "Update incident status", description = "Transition incident through its lifecycle states")
    public ResponseEntity<IncidentResponse> updateStatus(
            @PathVariable UUID id,
            @RequestParam IncidentStatus status) {

        // In Day 4: replace "SYSTEM" with the authenticated user from JWT
        return ResponseEntity.ok(incidentService.updateIncidentStatus(id, status, "SYSTEM"));
    }

    /**
     * GET /api/v1/incidents/{id}/investigations — Get agent investigation results for a specific incident.
     */
    @GetMapping("/incidents/{id}/investigations")
    @Operation(summary = "Get agent investigation results for an incident")
    public ResponseEntity<List<InvestigationResultResponse>> getInvestigationResults(@PathVariable UUID id) {
        return ResponseEntity.ok(incidentService.getInvestigationResults(id));
    }
}
