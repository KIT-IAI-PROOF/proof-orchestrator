/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.config;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Objects;

/**
 * Helper class for workflow start operation.
 * Options to define additional workflow execution like manual steps.
 */
public class RuntimeOptions {

    /**
     * For development, it is possible to start a workflow without blocks doing anything.
     * For testing purposes.
     */
    private boolean override;

    /**
     * For development, right now always false. Enables the possibility to manually go over the steps of a block.
     */
    private boolean manual;

    public RuntimeOptions() {

    }

    public RuntimeOptions(boolean override, boolean manual) {
        this.override = override;
        this.manual = manual;
    }

    public boolean isOverride() {
        return override;
    }

    public void setOverride(boolean override) {
        this.override = override;
    }

    public boolean isManual() {
        return manual;
    }

    public void setManual(boolean manual) {
        this.manual = manual;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RuntimeOptions that)) return false;
        return override == that.override && manual == that.manual;
    }

    @Override
    public int hashCode() {
        return Objects.hash(override, manual);
    }
}
