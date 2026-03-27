/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import edu.kit.iai.webis.prooforchestrator.config.OrchestrationConfig;
import edu.kit.iai.webis.prooforchestrator.container.BlockContainer;
import edu.kit.iai.webis.prooforchestrator.exception.ContainerException;
import edu.kit.iai.webis.proofutils.Executor;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import org.springframework.stereotype.Service;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * Service class for a local container orchestration based on background processes
 */
@Service
public class LocalProcessService {

    private final OrchestrationConfig orchestrationConfig;
    private final Executor executor;

    private PipedOutputStream outputStream;
    private PipedInputStream inputStream;
    private PipedInputStream pipedInputStream;
    private PipedOutputStream pipedOutputStream;


    public LocalProcessService(final OrchestrationConfig orchestrationConfig, final Executor executor) {
        this.orchestrationConfig = orchestrationConfig;
        this.executor = executor;
        LoggingHelper.debug().log("Running local ... ");
        this.setupIOStreams();
    }

    /**
     * Setup input and output pipe stream for the connection to the executed program
     */
    private void setupIOStreams() {
        try {
            this.outputStream = new PipedOutputStream();
            this.pipedInputStream = new PipedInputStream(this.outputStream);
            this.inputStream = new PipedInputStream();
            this.pipedOutputStream = new PipedOutputStream(this.inputStream);
        } catch (final Exception e) {
            LoggingHelper.error().exception(e).log(e.getMessage());
        }
    }

    /**
     * Start a container for a specific block
     *
     * @param blockContainer Container of the block
     * @param executionId    Execution id of the runtime
     */
    public void startProcess(final String command) {

        try {
            LoggingHelper.info().log("executing Workflow with command script %s ", command);
            this.executor.execute(command, this.inputStream, this.outputStream);

        } catch (final Exception e) {
            LoggingHelper.error().exception(e).log("run script could not be started, reason: ");
            throw new ContainerException("run script could not be started", e);
        }
    }

}
