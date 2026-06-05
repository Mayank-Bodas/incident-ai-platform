package com.incidentplatform.domain.enums;

/**
 * IncidentStatus — The Incident State Machine
 *
 * WHY a State Machine?
 * Incidents follow a strict lifecycle. Invalid transitions must be rejected.
 * E.g., you cannot go OPEN → CLOSED without INVESTIGATING → RESOLVED first.
 *
 * This is the State Machine / State Pattern (Gang of Four).
 * Interview: "How did you model the incident lifecycle?" → State Machine pattern
 *
 * Valid transitions:
 *   OPEN → INVESTIGATING (AI agents started)
 *   INVESTIGATING → RESOLVED (RCA complete, fix applied)
 *   RESOLVED → CLOSED (Post-mortem done, officially closed)
 *   RESOLVED → OPEN (Regression — fix didn't work, reopened)
 *
 * WHY allow RESOLVED → OPEN?
 * Real incidents regress. AWS, GitHub, and Stripe all have post-incident regressions.
 * If your state machine doesn't allow this, engineers create duplicate incidents.
 */
public enum IncidentStatus {
    OPEN,           // Created, not yet being investigated
    INVESTIGATING,  // AI agents analyzing the incident
    RESOLVED,       // Root cause found, fix applied
    CLOSED          // Post-mortem complete, officially done
}
