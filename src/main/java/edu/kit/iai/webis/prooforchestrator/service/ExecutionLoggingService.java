/*
 * Copyright (c) 2023
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics
 */

package edu.kit.iai.webis.prooforchestrator.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.logback.AmqpAppender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    public void setupExecutionLogging(String executionId) {
        String queueName = "logs.execution." + executionId;

        // Declare queue and bind to exchange
        Queue queue = QueueBuilder.durable(queueName).build();
        TopicExchange exchange = new TopicExchange("logs");
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(queueName);
        
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareBinding(binding);

        // Setup logback appender
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        
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

        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(amqpAppender);
    }

    public void removeExecutionLogging(String executionId) {
        String queueName = "logs.execution." + executionId;
        
        // Detach appender
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.detachAppender("AMQP-" + executionId);
        
        // Delete queue
        amqpAdmin.deleteQueue(queueName);
    }
}
