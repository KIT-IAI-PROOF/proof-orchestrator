/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.exception;

@SuppressWarnings("unused")
public class ScheduleException extends RuntimeException {

    public ScheduleException(String message, Throwable cause) {
        super(message, cause);
    }

    public ScheduleException(String message) {
        super(message);
    }

    public ScheduleException(Throwable cause) {
        super(cause);
    }
}
