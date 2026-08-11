/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.logback.AmqpAppender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import edu.kit.iai.webis.proofutils.LoggingHelper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
public class ExecutionLoggingService {

    @Value("${spring.rabbitmq.host}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.port}")
    private int rabbitPort;

    @Value("${spring.rabbitmq.username}")
    private String rabbitUsername;

    @Value("${spring.rabbitmq.password}")
    private String rabbitPassword;

    @Value("${app.name}")
    private String appName;

    private final AmqpAdmin amqpAdmin;

    public ExecutionLoggingService(AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    private String getQueueName(String executionId) {
        return "logs.execution." + executionId;
    }

    public void setupExecutionLogging(String executionId) {
        String queueName = this.getQueueName(executionId);

        // First, clean up ALL existing AMQP appenders for previous executions
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        
        // Get all appenders and remove any that start with "AMQP-"
        Iterator<Appender<ILoggingEvent>> appenderIter = rootLogger.iteratorForAppenders();
        List<Appender<ILoggingEvent>> appendersToRemove = new ArrayList<>();
        
        while (appenderIter.hasNext()) {
            Appender<ILoggingEvent> appender = appenderIter.next();
            if (appender.getName() != null && appender.getName().startsWith("AMQP-")) {
                appendersToRemove.add(appender);
                LoggingHelper.warn().log("Found existing AMQP appender: %s, will remove it", appender.getName());
            }
        }
        
        // Remove all old AMQP appenders
        for (Appender<ILoggingEvent> appender : appendersToRemove) {
            rootLogger.detachAppender(appender);
            appender.stop();
            LoggingHelper.info().log("Stopped and removed old appender: %s", appender.getName());
        }

        // Declare queue and bind to exchange
        Queue queue = QueueBuilder.durable(queueName).build();
        TopicExchange exchange = new TopicExchange("logs");
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(queueName);
        
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareBinding(binding);

        // Setup logback appender
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%d %p %t [%c] - <%m>%n");
        encoder.start();
        
        AmqpAppender amqpAppender = new AmqpAppender();
        amqpAppender.setContext(loggerContext);
        amqpAppender.setName("AMQP-" + executionId);
        amqpAppender.setHost(rabbitHost);
        amqpAppender.setPort(rabbitPort);
        amqpAppender.setUsername(rabbitUsername);
        amqpAppender.setPassword(rabbitPassword);
        amqpAppender.setApplicationId(appName + "-" + executionId);
        amqpAppender.setRoutingKeyPattern(queueName);
        amqpAppender.setExchangeName("logs");
        amqpAppender.setExchangeType("topic");
        amqpAppender.setDeclareExchange(true);
        amqpAppender.setGenerateId(true);
        amqpAppender.setCharset("UTF-8");
        amqpAppender.setDurable(true);
        amqpAppender.setEncoder(encoder);
        amqpAppender.start();

        rootLogger.addAppender(amqpAppender);
        LoggingHelper.info().log("Setup logging appender for execution: %s", executionId);
    }

    public void stopExecutionLogging(String executionId) {
        // Detach and stop appender
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        
        // Get the appender before detaching
        Appender<ILoggingEvent> appender = rootLogger.getAppender("AMQP-" + executionId);
        
        if (appender != null) {
            // Detach from logger
            rootLogger.detachAppender(appender);
            // Stop the appender to close connections
            appender.stop();
            LoggingHelper.info().log("Stopped and detached logging appender for execution: %s", executionId);
        } else {
            LoggingHelper.warn().log("No logging appender found for execution: %s", executionId);
        }
    }

    public void removeExecutionLogging(String executionId) {
        String queueName = this.getQueueName(executionId);
        
        // Detach and stop appender
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        
        Appender<ILoggingEvent> appender = rootLogger.getAppender("AMQP-" + executionId);
        if (appender != null) {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
        
        // Delete queue
        amqpAdmin.deleteQueue(queueName);
        LoggingHelper.info().log("Removed logging appender and queue for execution: %s", executionId);
    }
}
