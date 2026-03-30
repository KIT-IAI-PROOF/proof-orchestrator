/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.exception;

import org.springframework.web.bind.annotation.ResponseStatus;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@ResponseStatus(INTERNAL_SERVER_ERROR)
public class ConcurrentWorkflowException extends RuntimeException {

    public ConcurrentWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConcurrentWorkflowException(String message) {
        super(message);
    }
}
