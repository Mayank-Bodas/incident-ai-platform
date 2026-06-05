package com.incidentplatform.application.agent;

import com.incidentplatform.application.agent.model.AgentInput;
import com.incidentplatform.application.agent.model.AgentResult;

/**
 * IncidentAgent — Interface defining behavior for all incident investigation agents.
 */
public interface IncidentAgent {
    
    /**
     * Executes the agent's logic on the given input context.
     *
     * @param input the context containing incident details and previous agent outputs
     * @return the structured result from the agent execution
     */
    AgentResult execute(AgentInput input);

    /**
     * Returns the type identifier of the agent (e.g. "PLANNER", "LOG_METRICS", etc.).
     *
     * @return agent type string matching the database representation
     */
    String getAgentType();
}
