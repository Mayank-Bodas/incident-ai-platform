package com.incidentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.application.dto.request.CreateAlertRequest;
import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import com.incidentplatform.infrastructure.persistence.entity.AlertEntity;
import com.incidentplatform.infrastructure.persistence.entity.AuditLogEntity;
import com.incidentplatform.infrastructure.persistence.entity.IncidentEntity;
import com.incidentplatform.infrastructure.persistence.repository.AlertRepository;
import com.incidentplatform.infrastructure.persistence.repository.AuditLogRepository;
import com.incidentplatform.infrastructure.persistence.repository.IncidentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * IncidentIntegrationTest — End-to-end integration tests verifying the REST endpoints,
 * database persistence, state machine transitions, and Kafka message ingestion.
 *
 * WHY MockMvc + @SpringBootTest?
 * - MockMvc allows us to make HTTP requests against our API without launching a real server
 *   on a network port. This runs faster and avoids port collisions.
 * - It natively supports PATCH requests (unlike standard JDK HttpURLConnection used by TestRestTemplate).
 * - Bypasses HTTP network overhead but runs the full Spring context (filters, controllers, services, DB).
 */
@SpringBootTest
@AutoConfigureMockMvc
public class IncidentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    @AfterEach
    public void cleanup() {
        // Delete alerts first due to foreign key constraints
        alertRepository.deleteAll();
        incidentRepository.deleteAll();
        auditLogRepository.deleteAll();
    }

    @Test
    public void testAlertIngestionAndAsyncIncidentCreationFlow() throws Exception {
        // 1. Arrange: Create a request for alert ingestion
        String serviceName = "payment-service-" + UUID.randomUUID().toString().substring(0, 8);
        CreateAlertRequest request = CreateAlertRequest.builder()
                .title("Payment service latency high")
                .description("99th percentile response time is 3.5s")
                .severity(Severity.SEV2)
                .source("prometheus")
                .serviceName(serviceName)
                .environment("production")
                .rawPayload("{\"metric\":\"http_request_duration_seconds\",\"value\":3.5}")
                .build();

        // 2. Act: Send POST /api/v1/alerts (Async ingestion via Kafka)
        mockMvc.perform(post("/api/v1/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.service").value(serviceName))
                .andExpect(jsonPath("$.message").value("Alert received and queued for processing"));

        // 3. Assert: Verify async consumer processes the event and inserts database records
        // Since processing is async via Kafka, we use Awaitility to wait for the DB write.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<IncidentEntity> incidents = incidentRepository.findByServiceName(serviceName, 
                    org.springframework.data.domain.Pageable.ofSize(10)).getContent();
            assertThat(incidents).hasSize(1);
            
            IncidentEntity incident = incidents.get(0);
            assertThat(incident.getTitle()).contains("Payment service latency high");
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(incident.getSeverity()).isEqualTo(Severity.SEV2);

            List<AlertEntity> alerts = alertRepository.findAll();
            List<AlertEntity> incidentAlerts = alerts.stream()
                    .filter(a -> a.getIncident().getId().equals(incident.getId()))
                    .toList();
            assertThat(incidentAlerts).hasSize(1);
            assertThat(incidentAlerts.get(0).getTitle()).isEqualTo("Payment service latency high");
            assertThat(incidentAlerts.get(0).getSource()).isEqualTo("prometheus");

            // Verify Audit Logs
            List<AuditLogEntity> auditLogs = auditLogRepository.findAll();
            boolean hasIncidentAudit = auditLogs.stream()
                    .anyMatch(log -> "INCIDENT".equals(log.getEntityType()) && log.getEntityId().equals(incident.getId().toString()));
            assertThat(hasIncidentAudit).isTrue();
        });
    }

    @Test
    public void testIncidentStatusStateTransitionLifecycle() throws Exception {
        // 1. Arrange: Seed an incident directly in the database
        IncidentEntity incident = IncidentEntity.builder()
                .title("Database connection timeouts")
                .description("Postgres connection pool exhausted")
                .status(IncidentStatus.OPEN)
                .severity(Severity.SEV1)
                .serviceName("order-service")
                .environment("production")
                .createdBy("SYSTEM")
                .build();
        
        IncidentEntity saved = incidentRepository.save(incident);
        UUID incidentId = saved.getId();

        // 2. Act & Assert: Transition from OPEN -> INVESTIGATING (Allowed)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .param("status", "INVESTIGATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));

        // Verify state is updated in the database
        IncidentEntity updatedIncident = incidentRepository.findById(incidentId).orElseThrow();
        assertThat(updatedIncident.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);

        // 3. Act & Assert: Transition from INVESTIGATING -> RESOLVED (Allowed)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        updatedIncident = incidentRepository.findById(incidentId).orElseThrow();
        assertThat(updatedIncident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(updatedIncident.getResolvedAt()).isNotNull();

        // 4. Act & Assert: Transition from RESOLVED -> CLOSED (Allowed)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        updatedIncident = incidentRepository.findById(incidentId).orElseThrow();
        assertThat(updatedIncident.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(updatedIncident.getClosedAt()).isNotNull();

        // 5. Act & Assert: Transition from CLOSED -> OPEN (Not Allowed - Closed is terminal)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .param("status", "OPEN"))
                .andExpect(status().isConflict()) // 409 Conflict
                .andExpect(jsonPath("$.title").value("Invalid State Transition"))
                .andExpect(jsonPath("$.detail").value("Invalid state transition from 'CLOSED' to 'OPEN'"));
    }

    @Test
    public void testGetIncidentNotFound() throws Exception {
        UUID randomId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/incidents/" + randomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Incident Not Found"))
                .andExpect(jsonPath("$.detail").value("Incident not found with id: " + randomId));
    }

    @Test
    public void testAlertIngestionValidationErrors() throws Exception {
        // Create an invalid request (missing serviceName and source)
        CreateAlertRequest invalidRequest = CreateAlertRequest.builder()
                .title("") // Blank
                .severity(null) // Null
                .source("prometheus")
                .serviceName("")
                .build();

        mockMvc.perform(post("/api/v1/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.detail").value("Request validation failed. See 'errors' for details."))
                .andExpect(jsonPath("$.errors.title").value("Alert title is required"))
                .andExpect(jsonPath("$.errors.severity").value("Severity is required"))
                .andExpect(jsonPath("$.errors.serviceName").value("Service name is required"));
    }
}
