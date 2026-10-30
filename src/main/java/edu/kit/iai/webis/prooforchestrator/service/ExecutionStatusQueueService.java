/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.stereotype.Service;

import edu.kit.iai.webis.proofutils.LoggingHelper;

/**
 * Service for managing execution-specific status queues.
 * 
 * Each execution gets its own stream queue named:
 * "status.execution.<execution-id>"
 * 
 * This replaces the single global "execution-status-queue" with
 * per-execution stream queues for better isolation and scalability.
 */
@Service
public class ExecutionStatusQueueService {

    private final AmqpAdmin amqpAdmin;

    public ExecutionStatusQueueService(AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    /**
     * Create a status stream queue for the given execution.
     * 
     * @param executionId the execution ID
     */
    public void createStatusQueue(String executionId) {
        String queueName = getStatusQueueName(executionId);
        
        try {
            var queue = QueueBuilder.durable(queueName).stream().build();
            amqpAdmin.declareQueue(queue);
            LoggingHelper.info().log("Status stream queue '%s' created for execution %s", queueName, executionId);
        } catch (Exception e) {
            LoggingHelper.error()
                    .printStackTrace(e)
                    .log("Failed to create status queue '%s' for execution %s", queueName, executionId);
        }
    }

    /**
     * Delete the status stream queue for the given execution.
     * 
     * @param executionId the execution ID
     * @return true if the queue was deleted, false otherwise
     */
    public boolean deleteStatusQueue(String executionId) {
        String queueName = getStatusQueueName(executionId);
        
        try {
            boolean deleted = amqpAdmin.deleteQueue(queueName);
            if (deleted) {
                LoggingHelper.info().log("Status queue '%s' deleted for execution %s", queueName, executionId);
            } else {
                LoggingHelper.warn().log("Status queue '%s' could not be deleted for execution %s", queueName, executionId);
            }
            return deleted;
        } catch (Exception e) {
            LoggingHelper.error()
                    .printStackTrace(e)
                    .log("Error deleting status queue '%s' for execution %s", queueName, executionId);
            return false;
        }
    }

    /**
     * Get the status queue name for the given execution.
     * 
     * @param executionId the execution ID
     * @return the queue name: "status.execution.<execution-id>"
     */
    public static String getStatusQueueName(String executionId) {
        return "status.execution." + executionId;
    }
}
