package edu.kit.iai.webis.prooforchestrator.config;

/*
 * Copyright (c) 2022
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics
 */

import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * Helper class for workflow start operation.
 * Used for configuration object in http post to start workflows.
 */
public final class Configuration {

    /**
     * Static inputs demanded in the workflow.
     * Will be saved for future runs.
     */
    private Map<String, Map<String, String>> appliedInputs;

    /**
     * Options to define additional workflow execution like manual steps.
     */
    private RuntimeOptions runtimeOptions;

    public Configuration() {

    }

    public Configuration(Map<String, Map<String, String>> appliedInputs, RuntimeOptions runtimeOptions) {
        this.appliedInputs = appliedInputs;
        this.runtimeOptions = runtimeOptions;
    }

    public Map<String, Map<String, String>> getAppliedInputs() {
        return this.appliedInputs;
    }

    public void setAppliedInputs(Map<String, Map<String, String>> appliedInputs) {
        this.appliedInputs = appliedInputs;
    }

    public RuntimeOptions getRuntimeOptions() {
        return this.runtimeOptions;
    }

    public void setRuntimeOptions(RuntimeOptions runtimeOptions) {
        this.runtimeOptions = runtimeOptions;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Configuration that)) return false;
        return Objects.equals(this.appliedInputs, that.appliedInputs) && Objects.equals(this.runtimeOptions,
                that.runtimeOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.appliedInputs, this.runtimeOptions);
    }
}

