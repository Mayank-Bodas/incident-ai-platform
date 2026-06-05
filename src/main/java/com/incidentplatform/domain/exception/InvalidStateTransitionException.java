package com.incidentplatform.domain.exception;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String from, String to) {
        super(String.format("Invalid state transition from '%s' to '%s'", from, to));
    }
}
