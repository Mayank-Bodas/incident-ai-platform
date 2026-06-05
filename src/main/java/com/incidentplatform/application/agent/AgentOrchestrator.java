package com.incidentplatform.application.agent;

import com.incidentplatform.application.agent.impl.KnowledgeBaseAgent;
import com.incidentplatform.application.agent.impl.LogMetricsAgent;
import com.incidentplatform.application.agent.impl.PlannerAgent;
import com.incidentplatform.application.agent.impl.RcaRecommendationAgent;
import com.incidentplatform.application.agent.model.AgentInput;
import com.incidentplatform.application.agent.model.AgentResult;
import com.incidentplatform.application.service.IncidentService;
import com.incidentplatform.infrastructure.persistence.entity.IncidentEntity;
import com.incidentplatform.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.incidentplatform.infrastructure.persistence.repository.IncidentRepository;
import com.incidentplatform.infrastructure.persistence.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * AgentOrchestrator — Orchestrates the sequential execution of AI Agents to investigate and resolve incidents.
 *
 * WHY not `@Transactional` on the class?
 * The orchestrator performs heavy, slow AI model calls (taking seconds to minutes).
 * If the whole class/method were `@Transactional`, the database connection would be held open
 * for the entire duration of the AI calls, exhausting the Hikari connection pool.
 * Instead, we execute DB operations within short, separate transaction boundaries in `IncidentService`.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final IncidentRepository incidentRepository;
    private final IncidentService incidentService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    private final PlannerAgent plannerAgent;
    private final LogMetricsAgent logMetricsAgent;
    private final KnowledgeBaseAgent knowledgeBaseAgent;
    private final RcaRecommendationAgent rcaRecommendationAgent;

    /**
     * Entry point to trigger an asynchronous investigation pipeline for an incident.
     * Annotated with `@Async` to execute non-blocking in a separate thread pool.
     *
     * @param incidentId UUID of the incident to investigate
     */
    @Async
    public void investigateIncident(UUID incidentId) {
        log.info("Starting AI Agent orchestration pipeline for incident: {}", incidentId);

        try {
            // 1. Fetch current incident info
            IncidentEntity incident = incidentRepository.findById(incidentId)
                    .orElseThrow(() -> new IllegalArgumentException("Incident not found for ID: " + incidentId));

            // 2. Mark incident as INVESTIGATING
            incidentService.startInvestigation(incidentId);

            // 3. Step 1: Execute Planner Agent
            long startTime = System.currentTimeMillis();
            AgentInput input = AgentInput.builder()
                    .incidentId(incidentId)
                    .title(incident.getTitle())
                    .description(incident.getDescription())
                    .severity(incident.getSeverity())
                    .serviceName(incident.getServiceName())
                    .environment(incident.getEnvironment())
                    .build();

            AgentResult plannerResult = plannerAgent.execute(input);
            long plannerDuration = System.currentTimeMillis() - startTime;
            incidentService.saveInvestigationResult(
                    incidentId,
                    plannerAgent.getAgentType(),
                    plannerResult.findings(),
                    plannerResult.reasoning(),
                    plannerResult.confidenceScore(),
                    plannerDuration,
                    "llama3.2",
                    0
            );

            // 4. Step 2: Execute Log & Metrics Agent
            startTime = System.currentTimeMillis();
            input = AgentInput.builder()
                    .incidentId(incidentId)
                    .title(incident.getTitle())
                    .description(incident.getDescription())
                    .severity(incident.getSeverity())
                    .serviceName(incident.getServiceName())
                    .environment(incident.getEnvironment())
                    .plannerOutput(plannerResult.findings())
                    .build();

            AgentResult logMetricsResult = logMetricsAgent.execute(input);
            long logMetricsDuration = System.currentTimeMillis() - startTime;
            incidentService.saveInvestigationResult(
                    incidentId,
                    logMetricsAgent.getAgentType(),
                    logMetricsResult.findings(),
                    logMetricsResult.reasoning(),
                    logMetricsResult.confidenceScore(),
                    logMetricsDuration,
                    "llama3.2",
                    0
            );

            // 5. Query runbooks for KB Agent context
            List<KnowledgeDocumentEntity> docs = knowledgeDocumentRepository.findByServiceName(incident.getServiceName());
            List<String> runbooks = docs.stream()
                    .map(d -> String.format("Title: %s\nContent: %s", d.getTitle(), d.getContent()))
                    .toList();

            // 6. Step 3: Execute Knowledge Base Agent
            startTime = System.currentTimeMillis();
            input = AgentInput.builder()
                    .incidentId(incidentId)
                    .title(incident.getTitle())
                    .description(incident.getDescription())
                    .severity(incident.getSeverity())
                    .serviceName(incident.getServiceName())
                    .environment(incident.getEnvironment())
                    .plannerOutput(plannerResult.findings())
                    .logMetricsOutput(logMetricsResult.findings())
                    .runbooksContext(runbooks)
                    .build();

            AgentResult kbResult = knowledgeBaseAgent.execute(input);
            long kbDuration = System.currentTimeMillis() - startTime;
            incidentService.saveInvestigationResult(
                    incidentId,
                    knowledgeBaseAgent.getAgentType(),
                    kbResult.findings(),
                    kbResult.reasoning(),
                    kbResult.confidenceScore(),
                    kbDuration,
                    "llama3.2",
                    0
            );

            // 7. Step 4: Execute RCA & Recommendation Agent
            startTime = System.currentTimeMillis();
            input = AgentInput.builder()
                    .incidentId(incidentId)
                    .title(incident.getTitle())
                    .description(incident.getDescription())
                    .severity(incident.getSeverity())
                    .serviceName(incident.getServiceName())
                    .environment(incident.getEnvironment())
                    .plannerOutput(plannerResult.findings())
                    .logMetricsOutput(logMetricsResult.findings())
                    .runbooksContext(List.of(kbResult.findings())) // Feed the KB recovery steps
                    .build();

            AgentResult rcaResult = rcaRecommendationAgent.execute(input);
            long rcaDuration = System.currentTimeMillis() - startTime;
            incidentService.saveInvestigationResult(
                    incidentId,
                    rcaRecommendationAgent.getAgentType(),
                    rcaResult.findings(),
                    rcaResult.reasoning(),
                    rcaResult.confidenceScore(),
                    rcaDuration,
                    "llama3.2",
                    0
            );

            // 8. Auto-resolve the incident with RCA Summary
            incidentService.resolveIncident(incidentId, rcaResult.findings());
            log.info("AI Agent orchestration successfully completed for incident: {}", incidentId);

        } catch (Exception e) {
            log.error("Failed to run AI investigation pipeline for incident: {}", incidentId, e);
        }
    }
}
