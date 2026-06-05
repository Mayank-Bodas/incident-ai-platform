package com.incidentplatform.domain.exception;

/**
 * Domain exceptions — Business rule violations.
 *
 * WHY custom exceptions?
 * 1. Semantic meaning: IncidentNotFoundException is clearer than RuntimeException("not found")
 * 2. Exception hierarchy: allows catching specific vs general errors
 * 3. @ControllerAdvice maps these to HTTP responses cleanly
 *
 * WHY extend RuntimeException, not Exception?
 * Checked exceptions (extends Exception) force callers to handle or declare throws.
 * In Spring, unchecked (RuntimeException) exceptions trigger transaction rollback by default.
 * Modern Java practice: use unchecked for recoverable business errors.
 * Interview: "Checked vs Unchecked exceptions in Spring?" → Unchecked = transaction rollback
 */
public class IncidentNotFoundException extends RuntimeException {

    private final String incidentId;

    public IncidentNotFoundException(String incidentId) {
        super("Incident not found with id: " + incidentId);
        this.incidentId = incidentId;
    }

    public String getIncidentId() {
        return incidentId;
    }
}
