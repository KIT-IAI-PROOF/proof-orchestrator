/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.container;

import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.StepBasedConfiguration;
import edu.kit.iai.webis.proofutils.wrapper.StepSizeDefinition;
import edu.kit.iai.webis.proofutils.wrapper.Workflow;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.springframework.data.annotation.Transient;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;


/**
 * Wrapper class for blocks to add additional fields and functionality:
 * - BlockId in the context of the parent workflow to uniquely identify this block.
 * - WorkflowId of the parent workflow.
 * - Environment variables to add in the container.
 * - And RabbitMQ functionality for inputs and the tact.
 */
public class BlockContainer {

    /**
     * UUID to identify the BlockContainer, not the same as the respective Blocks id.
     */
    private String globalId;

    /**
     * Current status of the BlockContainer.
     * See {@link SimulationStatus}, e.g. {@link SimulationStatus#READY}
     */
    private SimulationStatus status;

    /**
     * Block instance managed by current {@link BlockContainer}. see {@link Block}
     */
    private Block block;

    /**
     * Integer to uniquely identify the block in the context of the parent workflow.
     */
//    private Integer localBlockId;
    private Integer index;

    /**
     * UUID of the parent workflow {@link Workflow}.
     */
    private String workflowId;

    /**
     * Environment variables to add in the container.
     * E.g. as input and output descriptions or file paths.
     */
    private Map<String, String> environmentVars;

    /**
     * Tracks the current step.
     */
    private Integer communicationPoint;

    /**
     * The end point.
     */
    private Integer endPoint;

    /**
     * The start point.
     */
    private Integer startPoint = 0;

    /**
     * Tracks the next (upcoming) stepSize to use.
     */
    private Integer communicationStepSize;


    /**
     * the {@link StepSizeDefinition} for the block, originated in the {@link Workflow}'s {@link StepBasedConfiguration}
     */
    private StepSizeDefinition stepSizeDefinition;

    @Transient
    private Integer simulationCommunicationPoint = 0;

    @Transient
    private final int[] relevantCommunicationPoints = null;

    //    @Transient
    /**
     * is the block relevant for shutdown when finished?.
     */
    private Boolean shutdownRelevant = false;

    /**
     * Default constructor.
     * Initializes UUID to reference block container in the context of a workflow execution.
     */
    public BlockContainer() {
        this.globalId = String.valueOf(UUID.randomUUID());
        this.communicationPoint = 0;
    }

    public String getGlobalId() {
        return this.globalId;
    }

    public void setGlobalId(String globalId) {
        this.globalId = globalId;
    }

    public SimulationStatus getStatus() {
        return this.status;
    }

    public void setStatus(SimulationStatus status) {
        this.status = status;
    }

    public Block getBlock() {
        return this.block;
    }

    public void setBlock(Block block) {
        this.block = block;
    }

    public Integer getIndex() {
        return this.index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getWorkflowId() {
        return this.workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public Map<String, String> getEnvironmentVars() {
        return this.environmentVars;
    }

    public void setEnvironmentVars(Map<String, String> environmentVars) {
        this.environmentVars = environmentVars;
    }

    public void addEnvironmentVars(Map<String, String> additionalEnvironmentVars) {
        if (this.environmentVars != null) {
            if (additionalEnvironmentVars != null) {
                this.environmentVars.putAll(additionalEnvironmentVars);
            }
        } else {
            this.environmentVars = additionalEnvironmentVars;
        }
    }

    public Integer getCommunicationPoint() {
        return this.communicationPoint;
    }

    public Integer getEndPoint() {
        return this.endPoint;
    }

    public Integer getStartPoint() {
        return this.startPoint;
    }

    public boolean isShutdownRelevant() {
        return this.shutdownRelevant;
    }

    public void setShutdownRelevance(Boolean shutdownRelevant) {
        this.shutdownRelevant = (shutdownRelevant != null ? shutdownRelevant : false);
    }

    /**
     * get the communication step size for a given communicationPoint
     *
     * @param communicationPoint the (current) communicationPoint
     * @return the associated communication step size, if there is one, otherwise,
     * the default communication step size is used (see {@link StepBasedConfiguration}).
     */
    public Integer getCommunicationStepSize(Integer communicationPoint) {
        if (this.stepSizeDefinition != null) {
            Integer stepSize = this.stepSizeDefinition.getStepSize(communicationPoint);
            if (stepSize != null) {
                LoggingHelper.trace().log(" stepSize for CP %d  is SPECIAL stepSize %d", communicationPoint, stepSize);
                return stepSize;
            } else {
                LoggingHelper.trace().log(" stepSize for CP %d: stepSize == NULL (StepSizeDefinition is given), return DEFAULT stepSize  %d", communicationPoint, this.communicationStepSize);
                return this.communicationStepSize;
            }
        }
        LoggingHelper.trace().log(" stepSize for CP %d  is DEFAULT stepSize %d", communicationPoint, this.communicationStepSize);
        return this.communicationStepSize;
    }

    /**
     * set the {@link StepBasedConfiguration} to set start and end points and step sizes
     *
     * @param stepBasedConfig
     */
    public void setConfiguration(StepBasedConfiguration stepBasedConfig) {
        Objects.requireNonNull(stepBasedConfig, "StepBasedConfiguration must be given");
        this.communicationStepSize = stepBasedConfig.getDefaultStepSize("" + this.index);
        this.communicationPoint = stepBasedConfig.getStartPoint("" + this.index);
        this.endPoint = stepBasedConfig.getEndPoint("" + this.index);
        this.startPoint = stepBasedConfig.getStartPoint("" + this.index);
        this.stepSizeDefinition = stepBasedConfig.getStepSizeDefinition("" + this.index);
    }

    /**
     * set the current communication point (CP) of the running workflow (container).
     * If the local CP of the BlockContainer
     * is equal to the given  simulationCommunicationPoint, it will be increased with the step sizes of the
     * stepSizeDefinitions for this BlockContainer.
     *
     * @param simulationCommunicationPoint the current communication point (CP) of the running workflow (container)
     */
    public void setSimulationCommunicationPoint(Integer simulationCommunicationPoint) {
        //LoggingHelper.trace().log("(Block %d) given simulationCommunicationPoint = %d (old=%d)", this.getLocalBlockId(), simulationCommunicationPoint, this.simulationCommunicationPoint);
        this.simulationCommunicationPoint = simulationCommunicationPoint;
    }


    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.JSON_STYLE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockContainer that)) {
            return false;
        }
        return Objects.equals(this.globalId, that.globalId) && this.status == that.status
                && Objects.equals(this.block, that.block)
                && Objects.equals(this.index, that.index)
                && Objects.equals(this.workflowId, that.workflowId)
                && Objects.equals(this.environmentVars, that.environmentVars)
                && Objects.equals(this.communicationPoint, that.communicationPoint)
                && Objects.equals(this.communicationStepSize, that.communicationStepSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.globalId,
                this.status,
                this.block,
                this.index,
                this.workflowId,
                this.environmentVars,
                this.communicationPoint,
                this.communicationStepSize);
    }

}
