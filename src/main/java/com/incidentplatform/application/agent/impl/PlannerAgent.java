package com.incidentplatform.application.agent.impl;

import com.incidentplatform.application.agent.IncidentAgent;
import com.incidentplatform.application.agent.model.AgentInput;
import com.incidentplatform.application.agent.model.AgentResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * PlannerAgent — Analyzes incident metadata and creates a step-by-step SRE investigation checklist.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PlannerAgent implements IncidentAgent {

    private final ChatLanguageModel chatLanguageModel;

    @Override
    public String getAgentType() {
        return "PLANNER";
    }

    @Override
    public AgentResult execute(AgentInput input) {
        log.info("Executing PlannerAgent for incident ID: {}", input.incidentId());
        
        String prompt = String.format(
            "You are the Planner Agent. Analyze the incident details below and create a step-by-step SRE investigation checklist.\n\n" +
            "Incident Title: %s\n" +
            "Description: %s\n" +
            "Severity: %s\n" +
            "Service: %s\n" +
            "Environment: %s\n\n" +
            "Provide your output exactly in this format:\n" +
            "FINDINGS:\n<list of steps to investigate>\n" +
            "REASONING:\n<why these steps are chosen>\n" +
            "CONFIDENCE:\n<a number between 0.0 and 1.0>",
            input.title(), input.description(), input.severity(), input.serviceName(), input.environment()
        );

        try {
            String response = chatLanguageModel.generate(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("Ollama LLM call failed in PlannerAgent: {}. Using simulated SRE response fallback.", e.getMessage());
            return getFallbackResult(input);
        }
    }

    private AgentResult parseResponse(String response) {
        String findings = "";
        String reasoning = "";
        BigDecimal confidenceScore = new BigDecimal("0.85");

        try {
            int findingsIdx = response.toUpperCase().indexOf("FINDINGS:");
            int reasoningIdx = response.toUpperCase().indexOf("REASONING:");
            int confidenceIdx = response.toUpperCase().indexOf("CONFIDENCE:");

            if (findingsIdx != -1 && reasoningIdx != -1) {
                findings = response.substring(findingsIdx + 9, reasoningIdx).trim();
            } else if (findingsIdx != -1) {
                findings = response.substring(findingsIdx + 9).trim();
            }

            if (reasoningIdx != -1) {
                if (confidenceIdx != -1 && confidenceIdx > reasoningIdx) {
                    reasoning = response.substring(reasoningIdx + 10, confidenceIdx).trim();
                } else {
                    reasoning = response.substring(reasoningIdx + 10).trim();
                }
            }

            if (confidenceIdx != -1) {
                String scoreStr = response.substring(confidenceIdx + 11).trim().replaceAll("[^0-9.]", "");
                if (!scoreStr.isEmpty()) {
                    confidenceScore = new BigDecimal(scoreStr);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", response, e);
            findings = response;
            reasoning = "Raw LLM response parsed with default confidence.";
        }

        return new AgentResult(findings, reasoning, confidenceScore);
    }

    private AgentResult getFallbackResult(AgentInput input) {
        String findings = String.format(
            "1. Verify network connectivity for service '%s' in environment '%s'.\n" +
            "2. Inspect CPU and Memory utilization metrics for the service pods.\n" +
            "3. Query Elasticsearch log records matching service '%s' for error-level messages.\n" +
            "4. Check database connection pool health and active session count.",
            input.serviceName(), input.environment(), input.serviceName()
        );
        String reasoning = String.format(
            "The incident is flagged as %s. For service '%s', sudden alert triggers are typically " +
            "caused by connectivity degradation, container resources limits, or underlying database bottlenecks.",
            input.severity(), input.serviceName()
        );
        return new AgentResult(findings, reasoning, new BigDecimal("0.90"));
    }
}
