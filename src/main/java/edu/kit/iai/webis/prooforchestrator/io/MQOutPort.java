/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.io;

import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.data.annotation.Transient;

import java.util.ArrayList;
import java.util.Objects;

public class MQOutPort {

    @Transient
    private final Exchange exchange;
    @Transient
    private final ArrayList<MQInPort> MQInPorts = new ArrayList<>();
    private final String exchangeName;

    public MQOutPort(final String exchangeName) {
        this.exchangeName = exchangeName;
        this.exchange = new FanoutExchange(exchangeName, true, false);
    }

    public void addInPort(final MQInPort port) {
        this.MQInPorts.add(port);
    }

    public String getName() {
        return this.exchange.getName();
    }

    public Exchange getExchange() {
        return this.exchange;
    }

    public ArrayList<MQInPort> getMQInPorts() {
        return this.MQInPorts;
    }

    public String getExchangeName() {
        return this.exchangeName;
    }

    @Override
    public String toString() {
        return "MQOutPort{" +
                "exchange=" + this.exchange +
                ", MQInPorts=" + this.MQInPorts +
                ", exchangeName='" + this.exchangeName + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MQOutPort mqOutPort)) {
            return false;
        }
        return Objects.equals(this.exchange, mqOutPort.exchange) && Objects.equals(this.MQInPorts, mqOutPort.MQInPorts) && Objects.equals(this.exchangeName, mqOutPort.exchangeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.exchange, this.MQInPorts, this.exchangeName);
    }
}
