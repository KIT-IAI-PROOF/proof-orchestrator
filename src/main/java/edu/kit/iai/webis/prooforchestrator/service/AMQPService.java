/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import edu.kit.iai.webis.prooforchestrator.io.MQInPort;
import edu.kit.iai.webis.prooforchestrator.io.MQOutPort;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service class for AMQP communication setup and handling
 */
@Service
public class AMQPService {

    private final AmqpAdmin amqpAdmin;

    private final List<String> queueNames = new ArrayList<String>();

    public AMQPService(final AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    /**
     * Declare a queue for an input in rabbitmq
     *
     * @param inPort Port of the Queue to declare
     */
    public void declareInputQueue(final MQInPort inPort) {
        String queueName = inPort.getName();
        this.amqpAdmin.deleteQueue(queueName);
        this.queueNames.add(queueName);
        this.amqpAdmin.declareQueue(inPort);
        LoggingHelper.info().log("Queue '" + queueName + "' declared");
    }

    /**
     * Declare queues for inputs in rabbitmq
     *
     * @param inPorts Ports of the queues to declare
     */
    public void declareInputQueues(List<MQInPort> inPorts) {
        inPorts.parallelStream().forEach(this::declareInputQueue);
    }

    /**
     * Declare an exchange for an output in rabbitmq
     *
     * @param outPort Port of the Exchange to declare
     */
    public void declareOutputExchange(final MQOutPort outPort) {
        this.amqpAdmin.deleteExchange(outPort.getName());
        try {
            this.amqpAdmin.declareExchange(outPort.getExchange());
        } catch (RuntimeException e) {
            LoggingHelper.error()
                    .printStackTrace(e)
                    .log("Error decaring Output Exchange '" + outPort.getName() + "'");
        }
        outPort.getMQInPorts().parallelStream().forEach((final MQInPort inPort) -> {
            try {
                this.amqpAdmin.declareBinding(BindingBuilder.bind(inPort).to(outPort.getExchange())
                        .with(outPort.getExchange().getName()).noargs());
                LoggingHelper.info().log("AMQPService:: Output Exchange '%s' declared for Input '%s'",
                        outPort.getName(), inPort.getActualName());
            } catch (RuntimeException e) {
                LoggingHelper.error().printStackTrace(e).log("Error decaring Output Exchange '%s' for Input '%s'\n",
                        outPort.getName(), inPort.getActualName());
            }
        });
    }

    /**
     * Declare exchanges for outputs in rabbitmq
     *
     * @param outPorts Ports of the exchanges to declare
     */
    public void declareOutputExchanges(List<MQOutPort> outPorts) {
        outPorts.parallelStream().forEach(this::declareOutputExchange);
    }

    /**
     * delete all input queues of a workflow container
     *
     * @param inPorts the list of {@link MQInPort}s of a workflow container
     */
    public void deleteInputQueues(List<MQInPort> inPorts) {
        inPorts.parallelStream().forEach((final MQInPort inPort) -> {
            this.amqpAdmin.deleteQueue(inPort.getName());
            LoggingHelper.info().log("Queue " + inPort.getName() + " deleted.");
        });
    }

    /**
     * delete all output exchanges of a workflow container
     *
     * @param outPorts the list of {@link MQOutPort}s of a workflow container
     */
    public void deleteOutputExchanges(List<MQOutPort> outPorts) {
        outPorts.parallelStream().forEach((final MQOutPort outPort) -> {
            this.amqpAdmin.deleteExchange(outPort.getExchangeName());
            LoggingHelper.info().log("Exchange " + outPort.getExchangeName() + " deleted.");
        });
    }

    /**
     * Declare a service queue and exchange
     *
     * @param queueName Name of the queue
     */
    public void declareServiceQueue(final String queueName) {
        this.queueNames.add(this.amqpAdmin.declareQueue(new Queue(queueName)));
        this.amqpAdmin.declareExchange(new FanoutExchange(queueName));
        LoggingHelper.info().log("Service Queue '" + queueName + "' declared");
    }

    /**
     * Delete service queue and exchange
     *
     * @param queueName Name of the queue
     * @return true, is the service queue and its exchange could be deleted, false, if not
     */
    public boolean deleteServiceQueue(String queueName) {
        return this.amqpAdmin.deleteQueue(queueName) && this.amqpAdmin.deleteExchange(queueName);
    }

    /**
     * Delete all declared service queues and exchange
     */
    public void deleteAllServiceQueues() {
        this.queueNames.forEach(n -> {
            if (this.deleteServiceQueue(n)) {
                LoggingHelper.info().log("Service Queue '%s' deleted", n);
            } else {
                LoggingHelper.error().log("Service Queue '%s' could not be deleted", n);
            }
        });
    }
}
