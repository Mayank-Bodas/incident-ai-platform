package com.incidentplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.application.dto.request.CreateAlertRequest;
import com.incidentplatform.application.dto.response.IncidentResponse;
import com.incidentplatform.domain.enums.IncidentStatus;
import com.incidentplatform.domain.enums.Severity;
import com.incidentplatform.domain.enums.UserRole;
import com.incidentplatform.infrastructure.persistence.entity.AlertEntity;
import com.incidentplatform.infrastructure.persistence.entity.AuditLogEntity;
import com.incidentplatform.infrastructure.persistence.entity.IncidentEntity;
import com.incidentplatform.infrastructure.persistence.entity.UserEntity;
import com.incidentplatform.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.incidentplatform.infrastructure.persistence.entity.InvestigationResultEntity;
import com.incidentplatform.infrastructure.persistence.repository.AlertRepository;
import com.incidentplatform.infrastructure.persistence.repository.AuditLogRepository;
import com.incidentplatform.infrastructure.persistence.repository.IncidentRepository;
import com.incidentplatform.infrastructure.persistence.repository.UserRepository;
import com.incidentplatform.infrastructure.persistence.repository.KnowledgeDocumentRepository;
import com.incidentplatform.infrastructure.persistence.repository.InvestigationResultRepository;
import com.incidentplatform.infrastructure.security.JwtService;
import com.incidentplatform.application.dto.request.IngestDocumentRequest;
import com.incidentplatform.infrastructure.search.repository.IncidentElasticsearchRepository;
import com.incidentplatform.infrastructure.search.repository.LogElasticsearchRepository;
import com.incidentplatform.infrastructure.search.document.IncidentDocument;
import com.incidentplatform.infrastructure.search.document.LogDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import com.incidentplatform.application.agent.impl.KnowledgeBaseAgent;
import com.incidentplatform.application.agent.model.AgentInput;
import com.incidentplatform.application.agent.model.AgentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
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
 * database persistence, state machine transitions, Kafka message ingestion, JWT/RBAC, Caching, and Rate Limiting.
 */
@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=latest")
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    private InvestigationResultRepository investigationResultRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private IncidentElasticsearchRepository incidentElasticsearchRepository;

    @Autowired
    private LogElasticsearchRepository logElasticsearchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeBaseAgent knowledgeBaseAgent;

    private String adminToken;
    private String engineerToken;
    private String viewerToken;

    @BeforeEach
    public void setup() {
        // Clear db tables (delete child entities first due to foreign keys)
        alertRepository.deleteAll();
        investigationResultRepository.deleteAll();
        incidentRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        knowledgeDocumentRepository.deleteAll();
        incidentElasticsearchRepository.deleteAll();
        logElasticsearchRepository.deleteAll();
        try {
            jdbcTemplate.execute("DELETE FROM knowledge_embeddings");
        } catch (Exception e) {
            // Ignore if table doesn't exist yet
        }

        // Seed test users for Role-Based Access Control
        UserEntity admin = userRepository.save(UserEntity.builder()
                .email("admin@test.com")
                .passwordHash("hashed")
                .role(UserRole.ROLE_ADMIN)
                .firstName("Admin")
                .lastName("User")
                .isActive(true)
                .build());

        UserEntity engineer = userRepository.save(UserEntity.builder()
                .email("engineer@test.com")
                .passwordHash("hashed")
                .role(UserRole.ROLE_ENGINEER)
                .firstName("Engineer")
                .lastName("User")
                .isActive(true)
                .build());

        UserEntity viewer = userRepository.save(UserEntity.builder()
                .email("viewer@test.com")
                .passwordHash("hashed")
                .role(UserRole.ROLE_VIEWER)
                .firstName("Viewer")
                .lastName("User")
                .isActive(true)
                .build());

        // Generate valid JWT tokens for tests
        adminToken = jwtService.generateToken(admin);
        engineerToken = jwtService.generateToken(engineer);
        viewerToken = jwtService.generateToken(viewer);
    }

    @AfterEach
    public void cleanup() {
        alertRepository.deleteAll();
        investigationResultRepository.deleteAll();
        incidentRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        knowledgeDocumentRepository.deleteAll();
        incidentElasticsearchRepository.deleteAll();
        logElasticsearchRepository.deleteAll();
        try {
            jdbcTemplate.execute("DELETE FROM knowledge_embeddings");
        } catch (Exception e) {
            // Ignore
        }

        // Clear redis keys generated during tests
        redisTemplate.delete(redisTemplate.keys("incident:*"));
        redisTemplate.delete(redisTemplate.keys("ratelimit:*"));
    }

    @Test
    public void testUnauthenticatedRequestsFail() throws Exception {
        // GET /api/v1/incidents without Authorization header should fail (403 or 401)
        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testRoleBasedAccessControlRestrictions() throws Exception {
        // 1. Viewer trying to ingest alert -> should get 403 Forbidden (restricted to ADMIN/ENGINEER)
        CreateAlertRequest request = CreateAlertRequest.builder()
                .title("Test alert")
                .severity(Severity.SEV3)
                .source("prometheus")
                .serviceName("test-service")
                .build();

        mockMvc.perform(post("/api/v1/alerts")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 2. Engineer trying to DELETE an incident -> should get 403 Forbidden (restricted to ADMIN)
        UUID incidentId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isForbidden());

        // 3. Admin trying to DELETE an incident -> should pass security filter and hit dispatcher servlet (405 Method Not Allowed)
        // because we don't have a DELETE handler, but the security check is bypassed successfully.
        mockMvc.perform(delete("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    public void testAlertIngestionAndAsyncIncidentCreationFlow() throws Exception {
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

        // Send request with ENGINEER token
        mockMvc.perform(post("/api/v1/alerts")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.service").value(serviceName));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<IncidentEntity> incidents = incidentRepository.findByServiceName(serviceName, 
                    org.springframework.data.domain.Pageable.ofSize(10)).getContent();
            assertThat(incidents).hasSize(1);
            
            IncidentEntity incident = incidents.get(0);
            assertThat(incident.getTitle()).contains("Payment service latency high");
            assertThat(incident.getStatus()).isIn(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING, IncidentStatus.RESOLVED);

            List<AlertEntity> alerts = alertRepository.findAll();
            List<AlertEntity> incidentAlerts = alerts.stream()
                    .filter(a -> a.getIncident().getId().equals(incident.getId()))
                    .toList();
            assertThat(incidentAlerts).hasSize(1);
        });
    }

    @Test
    public void testAiAgentOrchestrationPipelineE2E() throws Exception {
        String serviceName = "billing-service-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Seed a knowledge document for the service
        knowledgeDocumentRepository.save(KnowledgeDocumentEntity.builder()
                .title("Billing Database Connection Outage Runbook")
                .content("SOP: When billing DB experiences connection timeouts, verify the pool size, clear leaked connections, and restart.")
                .documentType("RUNBOOK")
                .serviceName(serviceName)
                .tags(List.of("postgres", "database"))
                .createdBy("ADMIN")
                .build());

        CreateAlertRequest request = CreateAlertRequest.builder()
                .title("Billing database connection failure")
                .description("Hikari pool-1 connection timeout exception")
                .severity(Severity.SEV1)
                .source("prometheus")
                .serviceName(serviceName)
                .environment("production")
                .rawPayload("{\"error\":\"ConnectionTimeoutException\"}")
                .build();

        // Ingest the alert
        mockMvc.perform(post("/api/v1/alerts")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // Wait for the AI Pipeline to finish executing all 4 agents and resolve the incident
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            List<IncidentEntity> incidents = incidentRepository.findByServiceName(serviceName, 
                    org.springframework.data.domain.Pageable.ofSize(10)).getContent();
            assertThat(incidents).hasSize(1);
            
            IncidentEntity incident = incidents.get(0);
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(incident.getRcaSummary()).isNotEmpty();
            assertThat(incident.getResolvedAt()).isNotNull();

            // Verify that all 4 agents wrote their results to DB
            List<InvestigationResultEntity> results = 
                    investigationResultRepository.findByIncidentId(incident.getId());
            assertThat(results).hasSize(4);
            
            // Check that we have one result for each agent type
            List<String> agentTypes = results.stream().map(r -> r.getAgentType()).toList();
            assertThat(agentTypes).containsExactlyInAnyOrder("PLANNER", "LOG_METRICS", "KNOWLEDGE", "RCA_RECOMMENDATION");
            
            for (InvestigationResultEntity res : results) {
                assertThat(res.getFindings()).isNotEmpty();
                assertThat(res.getReasoning()).isNotEmpty();
                assertThat(res.getConfidenceScore()).isNotNull();
            }
        });
    }

    @Test
    public void testIncidentStatusStateTransitionLifecycle() throws Exception {
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

        // 1. Transition with ENGINEER token (Allowed)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", "Bearer " + engineerToken)
                        .param("status", "INVESTIGATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));

        // 2. Transition from INVESTIGATING -> RESOLVED with ADMIN token (Allowed)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        // 3. Transition from RESOLVED -> CLOSED with ENGINEER token (Allowed)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", "Bearer " + engineerToken)
                        .param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 4. Invalid Transition from CLOSED -> OPEN with ENGINEER token (Not Allowed - Closed is terminal)
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", "Bearer " + engineerToken)
                        .param("status", "OPEN"))
                .andExpect(status().isConflict());
    }

    @Test
    public void testGetIncidentNotFound() throws Exception {
        UUID randomId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/incidents/" + randomId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Incident Not Found"));
    }

    @Test
    public void testAlertIngestionValidationErrors() throws Exception {
        CreateAlertRequest invalidRequest = CreateAlertRequest.builder()
                .title("")
                .severity(null)
                .source("prometheus")
                .serviceName("")
                .build();

        mockMvc.perform(post("/api/v1/alerts")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }

    @Test
    public void testRedisCacheAsideLogic() throws Exception {
        // Seed an incident
        IncidentEntity incident = incidentRepository.save(IncidentEntity.builder()
                .title("Cache Testing Incident")
                .description("Verifying Redis Cache-Aside")
                .status(IncidentStatus.OPEN)
                .severity(Severity.SEV3)
                .serviceName("cache-service")
                .environment("production")
                .createdBy("SYSTEM")
                .build());
        UUID incidentId = incident.getId();
        String cacheKey = "incident:" + incidentId.toString();

        // Ensure cache is empty
        redisTemplate.delete(cacheKey);

        // 1. First GET request (Cache MISS) -> loads from DB and caches it
        mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Cache Testing Incident"));

        // Verify that the incident was saved into Redis
        IncidentResponse cachedValue = (IncidentResponse) redisTemplate.opsForValue().get(cacheKey);
        assertThat(cachedValue).isNotNull();
        assertThat(cachedValue.title()).isEqualTo("Cache Testing Incident");

        // 2. Second GET request (Cache HIT) -> served directly from cache
        mockMvc.perform(get("/api/v1/incidents/" + incidentId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Cache Testing Incident"));

        // 3. Update Incident Status -> Evicts from cache
        mockMvc.perform(patch("/api/v1/incidents/" + incidentId + "/status")
                        .header("Authorization", "Bearer " + engineerToken)
                        .param("status", "INVESTIGATING"))
                .andExpect(status().isOk());

        // Verify that cache key is evicted (null in Redis)
        cachedValue = (IncidentResponse) redisTemplate.opsForValue().get(cacheKey);
        assertThat(cachedValue).isNull();
    }

    @Test
    public void testRateLimitingOnAlertEndpoint() throws Exception {
        CreateAlertRequest request = CreateAlertRequest.builder()
                .title("Rate limiting trigger alert")
                .severity(Severity.SEV4)
                .source("prometheus")
                .serviceName("rate-limited-service")
                .build();

        // The bucket capacity is configured to 10 tokens.
        // Sending 10 requests should succeed.
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/alerts")
                            .header("Authorization", "Bearer " + engineerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted());
        }

        // The 11th request should exceed the token bucket capacity and fail with 429 Too Many Requests
        mockMvc.perform(post("/api/v1/alerts")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").value("Alert ingestion rate limit exceeded. Please try again later."));
    }

    @Test
    public void testRunbookIngestionSecurityAndSuccess() throws Exception {
        IngestDocumentRequest request = new IngestDocumentRequest(
                "Auth Database Failure Recovery SOP",
                "SOP: Reset Hikari max pool size to 50 when connection timeout occurs.",
                "auth-service",
                List.of("postgres", "auth", "hikari")
        );

        // 1. Unauthenticated request should fail with 403 Forbidden or 401 Unauthorized
        mockMvc.perform(post("/api/v1/documents/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 2. Viewer role request should fail with 403 Forbidden
        mockMvc.perform(post("/api/v1/documents/ingest")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // 3. Engineer/Admin request should succeed with 201 Created
        mockMvc.perform(post("/api/v1/documents/ingest")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("Auth Database Failure Recovery SOP"))
                .andExpect(jsonPath("$.serviceName").value("auth-service"))
                .andExpect(jsonPath("$.tags", hasItem("postgres")))
                .andExpect(jsonPath("$.documentType").value("RUNBOOK"));

        // Verify database state
        List<KnowledgeDocumentEntity> docs = knowledgeDocumentRepository.findByServiceName("auth-service");
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getTitle()).isEqualTo("Auth Database Failure Recovery SOP");
    }

    @Test
    public void testPgVectorSemanticSearchAndRagAgentExecution() throws Exception {
        // Ingest runbook document using REST API
        IngestDocumentRequest request = new IngestDocumentRequest(
                "Redis Cache Purge Runbook",
                "SOP: Restart Redis node and flushall keys if cache inconsistency is observed.",
                "cache-service",
                List.of("redis", "cache")
        );

        mockMvc.perform(post("/api/v1/documents/ingest")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Verify document is split, embedded and saved in PostgreSQL (using repository check)
        List<KnowledgeDocumentEntity> docs = knowledgeDocumentRepository.findByServiceName("cache-service");
        assertThat(docs).hasSize(1);

        // Execute KnowledgeBaseAgent with a semantic search trigger input
        AgentInput agentInput = AgentInput.builder()
                .incidentId(UUID.randomUUID())
                .serviceName("cache-service")
                .title("Cache inconsistency found")
                .description("Redis keys mismatch after deployment, showing stale data")
                .severity(Severity.SEV2)
                .environment("production")
                .build();

        AgentResult result = knowledgeBaseAgent.execute(agentInput);

        // Verify RAG worked (findings will either extract from context or return custom SRE results referencing context)
        assertThat(result.findings()).isNotEmpty();
        assertThat(result.reasoning()).contains("cache-service");
        assertThat(result.confidenceScore()).isGreaterThanOrEqualTo(java.math.BigDecimal.ZERO);
    }

    @Test
    public void testElasticsearchIncidentAndLogSearchEndpoints() throws Exception {
        // 1. Index test incident in Elasticsearch
        IncidentDocument incidentDoc = IncidentDocument.builder()
                .id(UUID.randomUUID().toString())
                .title("Memory leak in payment microservice")
                .description("JVM garbage collection heap memory leak warnings")
                .status("OPEN")
                .severity("SEV1")
                .serviceName("payment-service")
                .environment("production")
                .rcaSummary("OutOfMemoryError threat from thread pool leaks")
                .createdAt(LocalDateTime.now())
                .build();

        incidentElasticsearchRepository.save(incidentDoc);

        // 2. Index test logs in Elasticsearch
        LogDocument logDoc = LogDocument.builder()
                .id(UUID.randomUUID().toString())
                .serviceName("payment-service")
                .logLevel("ERROR")
                .message("Fatal OutOfMemoryError in Java heap space")
                .timestamp(LocalDateTime.now())
                .build();

        logElasticsearchRepository.save(logDoc);

        // 3. Search Incidents via REST API with query parameter
        mockMvc.perform(get("/api/v1/search/incidents")
                        .header("Authorization", "Bearer " + viewerToken)
                        .param("query", "Memory leak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Memory leak in payment microservice"))
                .andExpect(jsonPath("$[0].rcaSummary").value("OutOfMemoryError threat from thread pool leaks"));

        // 4. Search Logs via REST API with serviceName and query parameters
        mockMvc.perform(get("/api/v1/search/logs")
                        .header("Authorization", "Bearer " + engineerToken)
                        .param("serviceName", "payment-service")
                        .param("query", "Fatal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message").value("Fatal OutOfMemoryError in Java heap space"))
                .andExpect(jsonPath("$[0].logLevel").value("ERROR"));

        // 5. Search Logs via REST API with serviceName only (returns logs in last 15 minutes)
        mockMvc.perform(get("/api/v1/search/logs")
                        .header("Authorization", "Bearer " + viewerToken)
                        .param("serviceName", "payment-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message").value("Fatal OutOfMemoryError in Java heap space"));
    }
}
