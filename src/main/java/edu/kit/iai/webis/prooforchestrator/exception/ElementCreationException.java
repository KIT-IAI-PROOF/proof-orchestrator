/*
 * Copyright (c) 2021
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics
 */

package edu.kit.iai.webis.prooforchestrator.exception;

import org.springframework.web.bind.annotation.ResponseStatus;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@ResponseStatus(INTERNAL_SERVER_ERROR)
public class ElementCreationException extends RuntimeException {

    public ElementCreationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ElementCreationException(String message) {
        super(message);
    }
}
