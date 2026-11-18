package com.chargeflow.service;

import java.util.List;

/** Input could not be accepted. Carries every problem found, not just the first. */
public class ValidationException extends RuntimeException {

    private final List<String> details;

    public ValidationException(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public ValidationException(String message) {
        this(message, List.of());
    }

    public List<String> details() { return details; }
}
