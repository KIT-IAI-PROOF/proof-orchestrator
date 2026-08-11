/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.io;

import org.springframework.amqp.core.Queue;
import org.springframework.data.annotation.Transient;

import java.util.Collection;
import java.util.Map;

public class MQInPort extends Queue {

    public MQInPort(final String name) {
        super(name);
    }

    @Transient
    @Override
    public Collection<?> getDeclaringAdmins() {
        return super.getDeclaringAdmins();
    }

    @Transient
    @Override
    public Map<String, Object> getArguments() {
        return super.getArguments();
    }

    @Override
    public String getActualName() {
        return super.getActualName();
    }

    @Override
    public String getName() {
        return super.getName();
    }

    @Transient
    @Override
    public boolean isDurable() {
        return super.isDurable();
    }

    @Transient
    @Override
    public boolean isExclusive() {
        return super.isExclusive();
    }

    @Transient
    @Override
    public boolean isAutoDelete() {
        return super.isAutoDelete();
    }
}
