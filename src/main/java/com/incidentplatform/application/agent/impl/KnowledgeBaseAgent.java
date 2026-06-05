package com.incidentplatform.application.agent.impl;

import com.incidentplatform.application.agent.IncidentAgent;
import com.incidentplatform.application.agent.model.AgentInput;
import com.incidentplatform.application.agent.model.AgentResult;
import com.incidentplatform.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.incidentplatform.infrastructure.persistence.repository.KnowledgeDocumentRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * KnowledgeBaseAgent — Uses Vector Search (RAG) to locate relevant troubleshooting steps for the incident.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseAgent implements IncidentAgent {

    private final ChatLanguageModel chatLanguageModel;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Override
    public String getAgentType() {
        return "KNOWLEDGE";
    }

    @Override
    public AgentResult execute(AgentInput input) {
        log.info("Executing KnowledgeBaseAgent (RAG vector search) for incident ID: {}", input.incidentId());

        String context = "";
        List<EmbeddingMatch<TextSegment>> matches = List.of();

        try {
            // 1. Generate query search context
            String queryText = String.format("Service: %s. Title: %s. Description: %s", 
                    input.serviceName(), input.title(), input.description());

            // 2. Query pgvector store for semantic matches
            var queryEmbedding = embeddingModel.embed(queryText).content();
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(3)
                    .minScore(0.3) // Lower threshold to capture partially matching runbooks
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            matches = searchResult.matches();

            if (!matches.isEmpty()) {
                context = matches.stream()
                        .map(match -> String.format("[Score: %.2f] Runbook context: %s", 
                                match.score(), match.embedded().text()))
                        .collect(Collectors.joining("\n---\n"));
                log.info("RAG: found {} relevant runbook chunks via vector search.", matches.size());
            }
        } catch (Exception e) {
            log.warn("Vector search failed, falling back to direct service name lookup: {}", e.getMessage());
        }

        // 3. Fallback: query PostgreSQL standard relational tables by service name
        if (context.isEmpty()) {
            List<KnowledgeDocumentEntity> docs = knowledgeDocumentRepository.findByServiceName(input.serviceName());
            if (docs.isEmpty()) {
                context = "No specific runbooks found for service " + input.serviceName() + ".";
            } else {
                context = docs.stream()
                        .map(d -> "Title: " + d.getTitle() + "\nContent: " + d.getContent())
                        .collect(Collectors.joining("\n---\n"));
                log.info("RAG Fallback: retrieved {} matching runbooks by service name.", docs.size());
            }
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
            // Map matches back to entities for getFallbackResult signature compatibility
            return getFallbackResult(input, context);
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

    private AgentResult getFallbackResult(AgentInput input, String context) {
        String findings;
        String reasoning;
        if (context == null || context.contains("No specific runbooks found")) {
            findings = "1. Scale service capacity or check dependencies.\n" +
                       "2. Restart service instance to release connection leaks.";
            reasoning = "No matching service runbooks found. Recommending generic service recovery steps.";
        } else {
            findings = "Extracted steps: " + context;
            reasoning = "Retrieved matching runbooks via RAG context: " + input.serviceName();
        }
        return new AgentResult(findings, reasoning, new BigDecimal("0.80"));
    }
}
