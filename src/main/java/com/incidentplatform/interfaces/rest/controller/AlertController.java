package com.incidentplatform.interfaces.rest.controller;

import com.incidentplatform.application.dto.request.CreateAlertRequest;
import com.incidentplatform.application.dto.response.AlertResponse;
import com.incidentplatform.application.dto.response.IncidentResponse;
import com.incidentplatform.application.service.IncidentService;
import com.incidentplatform.domain.enums.IncidentStatus;
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

    /**
     * POST /api/v1/alerts — Receive an alert from a monitoring system.
     *
     * WHY POST and not PUT?
     * POST = create a new resource (you don't know the ID yet)
     * PUT = create or replace a resource at a specific URL (you know the ID)
     * Alert ingestion = POST. HTTP semantics matter.
     *
     * WHY @Valid?
     * Triggers Bean Validation on the request body.
     * Without @Valid: @NotBlank, @Size annotations do nothing.
     * With @Valid: violations throw MethodArgumentNotValidException → caught by GlobalExceptionHandler.
     */
    @PostMapping("/alerts")
    @Operation(summary = "Ingest an alert", description = "Receives an alert from monitoring systems and creates/links to an incident")
    public ResponseEntity<AlertResponse> ingestAlert(@Valid @RequestBody CreateAlertRequest request) {
        AlertResponse response = incidentService.processAlert(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
}
