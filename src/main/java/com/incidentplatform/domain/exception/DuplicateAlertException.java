package com.incidentplatform.domain.exception;

public class DuplicateAlertException extends RuntimeException {
    public DuplicateAlertException(String fingerprint) {
        super("Duplicate alert detected with fingerprint: " + fingerprint + ". Skipping incident creation.");
    }
}
