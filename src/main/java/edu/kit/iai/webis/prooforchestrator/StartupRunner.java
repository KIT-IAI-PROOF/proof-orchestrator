/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import edu.kit.iai.webis.proofutils.LoggingHelper;

@Profile({"dev", "prod", "debug"})
@Component
public class StartupRunner implements CommandLineRunner {

    @Value("${app.name}")
    private String appName;

	@Value("${proof.orchestration.logLevel:INFO}")
	private String loggingLevel;

	private final AmqpAdmin amqpAdmin;

    public StartupRunner(@NonNull final AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    public void declareLogQueue() {
        final var queue = QueueBuilder.durable("logs." + this.appName).build();
        final var binding = BindingBuilder.bind(queue).to(new TopicExchange("logs")).with("logs." + this.appName);
        this.amqpAdmin.declareQueue(queue);
        this.amqpAdmin.declareBinding(binding);
    }

    /**
     * Clear all dirty executions from previous runtime
     *
     * @param args Runner arguments
     */
    @Override
    public void run(final String... args) {
        LoggingHelper.logSourcePosition(true);
        // only if nothing works getting the level from logback.xml:
        LoggingHelper.setLogLevel(this.loggingLevel);
        LoggingHelper.info().log("Current LOG Level is " +  LoggingHelper.getLogLevel() );
        try {
            this.declareLogQueue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
