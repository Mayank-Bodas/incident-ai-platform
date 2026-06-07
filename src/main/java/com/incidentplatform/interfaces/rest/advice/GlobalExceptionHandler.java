package com.incidentplatform.interfaces.rest.advice;

import com.incidentplatform.domain.exception.DuplicateAlertException;
import com.incidentplatform.domain.exception.IncidentNotFoundException;
import com.incidentplatform.domain.exception.InvalidStateTransitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler — Centralized error handling.
 *
 * WHY @RestControllerAdvice?
 * Without this: every controller has its own try-catch → code duplication.
 * With this: ONE place handles all exceptions for ALL controllers.
 * This is the AOP (Aspect-Oriented Programming) principle.
 * SOLID: Single Responsibility — error handling in one place, not scattered.
 *
 * WHY ProblemDetail (RFC 7807)?
 * Industry standard for HTTP error responses. Used by Spring Boot 3+ natively.
 * Structure: type, title, status, detail, instance + custom properties.
 * Clients know exactly what to expect. Standardized parsing in frontend SDKs.
 * Interview: "How do you design error responses in REST APIs?" → RFC 7807 Problem Details
 *
 * Before Spring Boot 3: custom ErrorResponse class
 * Spring Boot 3+: ProblemDetail built-in. Always use standards.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ==================== 404 Not Found ====================
    @ExceptionHandler(IncidentNotFoundException.class)
    public ProblemDetail handleIncidentNotFound(IncidentNotFoundException ex) {
        log.warn("Incident not found: {}", ex.getIncidentId());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problem.setTitle("Incident Not Found");
        problem.setType(URI.create("https://incidentplatform.com/errors/incident-not-found"));
        problem.setProperty("incidentId", ex.getIncidentId());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ==================== 409 Conflict ====================
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidStateTransitionException ex) {
        log.warn("Invalid state transition attempted: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problem.setTitle("Invalid State Transition");
        problem.setType(URI.create("https://incidentplatform.com/errors/invalid-transition"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ==================== 409 Conflict - Duplicate ====================
    @ExceptionHandler(DuplicateAlertException.class)
    public ProblemDetail handleDuplicateAlert(DuplicateAlertException ex) {
        log.info("Duplicate alert rejected: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problem.setTitle("Duplicate Alert");
        return problem;
    }

    // ==================== 400 Validation Errors ====================
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        // WHY collect all field errors?
        // Return ALL validation failures at once, not just the first one.
        // UX: client fixes all 5 errors at once, not one at a time.
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            fieldErrors.put(fieldName, message);
        });

        log.warn("Validation failed: {}", fieldErrors);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed. See 'errors' for details."
        );
        problem.setTitle("Validation Error");
        problem.setProperty("errors", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ==================== 405 Method Not Allowed ====================
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.warn("HTTP method not supported: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED,
                ex.getMessage()
        );
        problem.setTitle("Method Not Allowed");
        problem.setType(URI.create("https://incidentplatform.com/errors/method-not-allowed"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ==================== 403 Forbidden ====================
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Access Denied: You do not have permissions to perform this action."
        );
        problem.setTitle("Access Denied");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ==================== 401 Unauthorized ====================
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(org.springframework.security.core.AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                ex.getMessage()
        );
        problem.setTitle("Unauthorized");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ==================== 404 Not Found - Missing Resource ====================
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Resource not found: " + ex.getResourcePath()
        );
        problem.setTitle("Resource Not Found");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    // ==================== 500 Internal Server Error ====================
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        // WHY log at ERROR level?
        // Unexpected exceptions = bugs. ERROR = triggers alerts in production (PagerDuty, Slack).
        log.error("Unexpected error occurred", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Our team has been notified."
                // WHY generic message?
                // Never expose internal error details to clients in production.
                // Stack traces reveal architecture details = security risk.
                // Correlation ID (from distributed tracing) lets ops find the real error in logs.
        );
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}

