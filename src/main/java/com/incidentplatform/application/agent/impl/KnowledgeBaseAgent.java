package com.incidentplatform.application.agent.impl;

import com.incidentplatform.application.agent.IncidentAgent;
import com.incidentplatform.application.agent.model.AgentInput;
import com.incidentplatform.application.agent.model.AgentResult;
import com.incidentplatform.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.incidentplatform.infrastructure.persistence.repository.KnowledgeDocumentRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * KnowledgeBaseAgent — Queries PostgreSQL for relevant runbooks and extracts the recovery steps.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseAgent implements IncidentAgent {

    private final ChatLanguageModel chatLanguageModel;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Override
    public String getAgentType() {
        return "KNOWLEDGE";
    }

    @Override
    public AgentResult execute(AgentInput input) {
        log.info("Executing KnowledgeBaseAgent for incident ID: {}", input.incidentId());

        List<KnowledgeDocumentEntity> docs = knowledgeDocumentRepository.findByServiceName(input.serviceName());
        
        String context = "";
        if (docs.isEmpty()) {
            context = "No specific runbooks found for service " + input.serviceName() + ".";
        } else {
            context = docs.stream()
                .map(d -> "Title: " + d.getTitle() + "\nContent: " + d.getContent())
                .collect(Collectors.joining("\n---\n"));
        }

        String prompt = String.format(
            "You are the Knowledge Base Agent. Analyze the incident details and the runbooks context below to identify relevant recovery steps.\n\n" +
            "Incident Title: %s\n" +
            "Service: %s\n" +
            "Runbooks Context:\n%s\n\n" +
            "Provide your output exactly in this format:\n" +
            "FINDINGS:\n<relevant recovery steps from the runbooks>\n" +
            "REASONING:\n<why these runbooks match the incident>\n" +
            "CONFIDENCE:\n<a number between 0.0 and 1.0>",
            input.title(), input.serviceName(), context
        );

        try {
            String response = chatLanguageModel.generate(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("Ollama LLM call failed in KnowledgeBaseAgent: {}. Using simulated SRE response fallback.", e.getMessage());
            return getFallbackResult(input, docs);
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

    private AgentResult getFallbackResult(AgentInput input, List<KnowledgeDocumentEntity> docs) {
        String findings;
        String reasoning;
        if (docs.isEmpty()) {
            findings = "1. Scale service capacity or check dependencies.\n" +
                       "2. Restart service instance to release connection leaks.";
            reasoning = "No matching service runbooks found. Recommending generic service recovery steps.";
        } else {
            findings = docs.stream()
                .map(d -> "SOP Steps from runbook: " + d.getTitle() + "\n" + d.getContent())
                .collect(Collectors.joining("\n"));
            reasoning = "Retrieved matching runbooks from PostgreSQL based on service name: " + input.serviceName();
        }
        return new AgentResult(findings, reasoning, new BigDecimal("0.80"));
    }
}
