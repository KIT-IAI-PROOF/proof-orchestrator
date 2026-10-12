/*
 * Copyright (c) 2022
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics
 */

package edu.kit.iai.webis.prooforchestrator.exception;

public class ContainerException extends RuntimeException {

    public ContainerException(String message, Throwable cause) {
        super(message, cause);
    }

    public ContainerException(String message) {
        super(message);
    }

    public ContainerException(Throwable cause) {
        super(cause);
    }
}
