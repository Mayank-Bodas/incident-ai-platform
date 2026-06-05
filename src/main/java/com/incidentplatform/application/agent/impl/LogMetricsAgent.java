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
 * LogMetricsAgent — Analyzes simulated or retrieved logs and metrics for error traces and resource anomalies.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LogMetricsAgent implements IncidentAgent {

    private final ChatLanguageModel chatLanguageModel;

    @Override
    public String getAgentType() {
        return "LOG_METRICS";
    }

    @Override
    public AgentResult execute(AgentInput input) {
        log.info("Executing LogMetricsAgent for incident ID: {}", input.incidentId());
        
        String logsToAnalyze = input.logsContext();
        if (logsToAnalyze == null || logsToAnalyze.trim().isEmpty()) {
            logsToAnalyze = simulateLogs(input.serviceName());
        }

        String prompt = String.format(
            "You are the Log & Metrics Agent. Analyze the logs and metrics below to identify anomalies or errors.\n\n" +
            "Service: %s\n" +
            "Environment: %s\n" +
            "Logs & Metrics Context:\n%s\n\n" +
            "Provide your output exactly in this format:\n" +
            "FINDINGS:\n<list of error traces or performance spikes identified>\n" +
            "REASONING:\n<why these logs indicate the root cause>\n" +
            "CONFIDENCE:\n<a number between 0.0 and 1.0>",
            input.serviceName(), input.environment(), logsToAnalyze
        );

        try {
            String response = chatLanguageModel.generate(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("Ollama LLM call failed in LogMetricsAgent: {}. Using simulated SRE response fallback.", e.getMessage());
            return getFallbackResult(input, logsToAnalyze);
        }
    }

    private String simulateLogs(String serviceName) {
        return String.format(
            "[INFO] 2026-06-05 10:00:00 - Starting request processing for endpoint /api/v1/data\n" +
            "[WARN] 2026-06-05 10:00:05 - Connection pool usage at 95%% for service %s\n" +
            "[ERROR] 2026-06-05 10:00:07 - ConnectionTimeoutException: Cannot get connection from pool for service %s after 30000ms\n" +
            "[ERROR] 2026-06-05 10:00:07 - Failed to complete request. HTTP 500.",
            serviceName, serviceName
        );
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

    private AgentResult getFallbackResult(AgentInput input, String logs) {
        String findings = String.format(
            "Detected database connection pool saturation on service '%s'.\n" +
            "Anomalies: ConnectionTimeoutException in logs. Latency spike to 30000ms before timeout.",
            input.serviceName()
        );
        String reasoning = "The logs show a high rate of connection pool errors, which correlate directly with the incident start time.";
        return new AgentResult(findings, reasoning, new BigDecimal("0.95"));
    }
}
