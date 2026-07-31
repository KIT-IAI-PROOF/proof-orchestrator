/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import edu.kit.iai.webis.prooforchestrator.container.BlockContainer;
import edu.kit.iai.webis.prooforchestrator.exception.ElementCreationException;
import edu.kit.iai.webis.prooforchestrator.exception.SetupException;
import edu.kit.iai.webis.prooforchestrator.io.MQInPort;
import edu.kit.iai.webis.prooforchestrator.io.MQOutPort;
import edu.kit.iai.webis.prooforchestrator.util.BlockHelper;
import edu.kit.iai.webis.prooforchestrator.util.StatusHelper;
import edu.kit.iai.webis.prooforchestrator.util.StringTemplates;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.MessageBuilder;
import edu.kit.iai.webis.proofutils.helper.NameHelper;
import edu.kit.iai.webis.proofutils.io.MQSyncProducer;
import edu.kit.iai.webis.proofutils.io.MQValueProducer;
import edu.kit.iai.webis.proofutils.message.MessageType;
import edu.kit.iai.webis.proofutils.message.NotifyMessage;
import edu.kit.iai.webis.proofutils.message.SyncMessage;
import edu.kit.iai.webis.proofutils.message.ValueMessage;
import edu.kit.iai.webis.proofutils.model.CommunicationType;
import edu.kit.iai.webis.proofutils.model.SimulationPhase;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.model.SimulationStrategy;
import edu.kit.iai.webis.proofutils.service.ConfigManagerService;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Execution;
import edu.kit.iai.webis.proofutils.wrapper.Workflow;

@Service
public class WorkflowService {

    private final ConfigManagerService configManagerService;
    private final StatusHelper statusHelper;
    private Execution execution = null;
    private String executionId;
    private Workflow workflow = null;
    private List<MQInPort> inPorts = new ArrayList<>();
    private List<MQOutPort> outPorts = new ArrayList<>();

    private final Map<Integer, BlockContainer> blockContainerMap = new HashMap<Integer, BlockContainer>();
    /**
     * Current status of the workflow service.
     * See {@link SimulationStatus}, e.g. {@link SimulationStatus#CREATED}
     */
    private SimulationStatus simulationStatus;
    private int endPoint;
    private int maxBlockEndPoint = 0;
    private Integer currentCommunicationPoint = 0;

    private SimulationStrategy simulationStrategy;


    public WorkflowService(final StatusHelper statusHelper, final ConfigManagerService configManagerService) {
        this.configManagerService = configManagerService;
        this.statusHelper = statusHelper;
    }

    protected void initialize(Execution execution) {
        this.execution = execution;
        this.executionId = execution.getId();
        this.workflow = execution.getWorkflow();
        this.simulationStrategy = this.workflow.getSimulationStrategy();
        this.createBlockContainers();
        this.currentCommunicationPoint = 0;
        this.endPoint = this.workflow.getStepBasedConfig().getEndPoint();
    }

    public SimulationStatus getSimulationStatus() {
        return this.simulationStatus;
    }

    /**
     * set the new status ({@link SimulationStatus}) for the simulation
     *
     * @param executionStatus the new status of the workflow simulation
     */
    public void setSimulationStatus(SimulationStatus executionStatus) {
        LoggingHelper.debug().log("SET WFS STATUS from %s to %s ... ", this.simulationStatus, executionStatus);
        this.simulationStatus = executionStatus;
        this.configManagerService.saveExecutionStatus(this.executionId, executionStatus);
    }


    public List<MQInPort> getInPorts() {
        return this.inPorts;
    }

    public List<MQOutPort> getOutPorts() {
        return this.outPorts;
    }

    /**
     * get the duration of a step of the underlying step-based workflow
     *
     * @return the duration as long value
     */
    public Long getStepBasedDuration() {
        return this.workflow.getStepBasedConfig().getDuration();
    }

    /**
     * get the start time of the underlying step-based workflow.
     * The start time defines the start delay in milliseconds,
     * i.e. how many milliseconds should pass before the process starts
     *
     * @return the start time
     */
    public Long getStepBasedStartTime() {
        return this.workflow.getStepBasedConfig().getStartTime();
    }

    public Workflow getWorkflow() {
        return this.workflow;
    }

    public String getExecutionId() {
        return this.executionId;
    }

    /**
     * check whether the workflow is of the same {@link SimulationStrategy} as the given one
     *
     * @param action the {@link SimulationStrategy} to compare with
     * @return true, if the {@link SimulationStrategy} types are identical, false, if not
     */
    public boolean isWorkflowSimulationStrategy(SimulationStrategy action) {
        return this.simulationStrategy.equals(action);
    }

    /**
     * Check whether the Workflow can be started. This is possible, if the initial
     * communication point of a {@link BlockContainer}
     * is not greater than the configured end point.
     *
     * @return true, if it is startable, if the initial
     * communication point is not greater than the configured end point.
     * <br><br>
     */
    public boolean isStartable() {
        boolean startable = true;
        for (BlockContainer b : this.blockContainerMap.values()) {
            if (b.getCommunicationPoint() > b.getEndPoint()) {
                LoggingHelper.error().globalBlockId("" + b.getIndex())
                        .log("CP=%d > EndPoint=%d  ==> Workflow can not be started!", b.getCommunicationPoint(),
                                b.getEndPoint());
                startable = false;
            }
        }
        if( startable && this.statusHelper.areAllStatus(SimulationStatus.VALUES_SET ) ) {
			return true;
		}
        return startable;
    }

    /**
     * set the next step (increase the CommunicationPoint number) for a block
     *
     * @param localBlockId the local block id of the block (e.g., 0, 1, 2, 3, ...)
     */
    public void prepareNextStep(Integer localBlockId) {
        final BlockContainer blockContainer = this.blockContainerMap.get(localBlockId);
        if (blockContainer != null) {
            this.nextStep(blockContainer);
        } else {
            throw new IllegalArgumentException("no BlockContainer found for localBlockId " + localBlockId);
        }
    }

    /**
     * set the next step (increase the CommunicationPoint number) for all blocks
     */
    public void prepareNextStepForAllBlocks() {
    	System.out.println("\nWFS:: preparing next step for all blocks\n");
        for (BlockContainer blockContainer : this.blockContainerMap.values()) {
            this.nextStep(blockContainer);
        }
    }

    private void nextStep(BlockContainer blockContainer) {
        // process only, if the blockContainer has an allowed status
    	LoggingHelper.trace().log("CP=%d, Status=%s", this.currentCommunicationPoint, blockContainer.getStatus() );
        switch (blockContainer.getStatus()) {
	        case EXECUTION_STEP_FINISHED, READY, CREATED, INITIALIZED, WAITING, VALUES_SET -> {
				blockContainer.setSimulationCommunicationPoint(this.currentCommunicationPoint);
				this.statusHelper.setBlockStatus(blockContainer, SimulationStatus.READY);
			}
			default ->
				// do nothing when status = EXECUTION_FINISHED, FINALIZED, ERROR_INIT, ERROR_STEP, ERROR_FINALIZE, ABORTED, STOPPED
			{
				LoggingHelper.warn().withBorder().messageColor(Colors.ANSI_GREEN_BOLD).log( "BlockContainer (%d) has the status %s -> not prepared for next step.".formatted(blockContainer.getIndex(), blockContainer.getStatus()  ) );
			}
		}
    }

    /**
     * set the same step (do not increase the CommunicationPoint number) for all blocks to retry the step
     */
    public void prepareRetryStep() {
    	for (BlockContainer blockContainer : this.blockContainerMap.values()) {
    		if( blockContainer.getStatus() == SimulationStatus.RETRY ) {
    			blockContainer.setSimulationCommunicationPoint(this.currentCommunicationPoint - 1);  // CP was increased last SYNC
    	    	LoggingHelper.debug().log("BlockContainer %d: Status=%s, CP=%d (reduced)", (this.currentCommunicationPoint - 1), blockContainer.getStatus(), blockContainer.getIndex() );
    		}
    	}
    }

    /**
     *
     * @param syncProducer
     * @param simulationPhase
     * @param syncMessage
     * @return true, if the simulation needs to be ended, false, otherwise
     */
    private boolean checkSimulationEndReached(final MQSyncProducer syncProducer,
    		final SimulationPhase simulationPhase,
    		final SyncMessage syncMessage) {
        if( simulationPhase != SimulationPhase.FINALIZE && simulationPhase != SimulationPhase.SHUTDOWN ) {
        	if( this.currentCommunicationPoint >= this.maxBlockEndPoint || this.currentCommunicationPoint >= this.endPoint ) {
        		LoggingHelper.info().log("maximal endpoint reached for a block (max=%d) or the whole workflow (%d)-> finalizing workflow ... ",
        				this.maxBlockEndPoint, this.endPoint );
        		this.blockContainerMap.values().forEach((final var blockContainer) -> {
        			this.buildAndSendSyncMessage(syncProducer, blockContainer, SimulationPhase.FINALIZE, syncMessage);
        		});
        		return true;
        	}
        }

        if (simulationPhase.equals(SimulationPhase.SHUTDOWN)) {
        	// get the initiating block and send a SYNC to all blockContainers that are not already shut down
        	LoggingHelper.info().log("SHUTDOWN SYNC message will be sent to all block containers" );
            this.blockContainerMap.values().forEach((final var blockContainer) -> {
            	if( (blockContainer.getStatus() != SimulationStatus.SHUT_DOWN )) {
            		this.buildAndSendSyncMessage(syncProducer, blockContainer, SimulationPhase.SHUTDOWN, syncMessage);
            	}
        	});
            return true;
        }
        else if (simulationPhase.equals(SimulationPhase.FINALIZE)) {
        	// get the initiating block and send a SYNC to all blockContainers that are not already finalized or shut down
        	LoggingHelper.info()
        	.log("FINALIZE SYNC message will be sent to all block containers ");
        	this.blockContainerMap.values().forEach((final var blockContainer) -> {
        		if( blockContainer.getStatus() != SimulationStatus.FINALIZED
        				&&  (blockContainer.getStatus() != SimulationStatus.SHUT_DOWN )) {
        			this.buildAndSendSyncMessage(syncProducer, blockContainer, SimulationPhase.FINALIZE, syncMessage);
        		}
        	});
        	return true;
        }
        return false;
    }


    /**
     * Send SYNC Message to all {@link BlockContainer}s
     *
     * @param syncProducer the producer (sender) of a sync message
     * @param simulationPhase the simulation phase
     */
    public void sendSyncMessage(final MQSyncProducer syncProducer, final SimulationPhase simulationPhase) {
        LoggingHelper.trace().log("  (Phase: " + simulationPhase + ")  CURRENT CP=%d", this.currentCommunicationPoint);

        final SyncMessage syncMessage = (SyncMessage) MessageBuilder.init(MessageType.SYNC)
                .workflowId(this.workflow.getId())
                .communicationPoint(this.currentCommunicationPoint)
                .build();

        if( this.checkSimulationEndReached( syncProducer, simulationPhase, syncMessage)) {
        	LoggingHelper.debug().log("simulation end reached!");
			return;
		}

        if( this.simulationStrategy == SimulationStrategy.WAIT_AND_RETRY
        	&& this.statusHelper.isAnyStatus(SimulationStatus.RETRY) )
		{
        	System.out.println("---------------------- sendSyncMessage (2) ---------------------------");
        	this.blockContainerMap.values().forEach((final var blockContainer) -> {
        		if( blockContainer.getStatus() == SimulationStatus.RETRY ) {
        			LoggingHelper.debug().log("--------> WFS::sendSyncMessage(RETRY):   Block %d:  %s ... CP=%d.  & setting " +
        					"Status to WAITING ", blockContainer.getIndex(), StringTemplates.EXECUTING_STEP_TACT, this.currentCommunicationPoint);
        			this.statusHelper.setBlockStatus(blockContainer, SimulationStatus.WAITING);
        			syncMessage.setCommunicationPoint(this.currentCommunicationPoint);  // send the same CP as before
        			this.buildAndSendSyncMessage(syncProducer, blockContainer, simulationPhase, syncMessage);
        		}
        	});
        	return;
		}

		// increase the current communication point, number '0' is used for block initialization
		if( simulationPhase != SimulationPhase.INIT ) {
			this.currentCommunicationPoint++;
			LoggingHelper.debug().messageColor(Colors.ANSI_RED_BOLD).log("sendSyncMessage():: currentCommunicationPoint increased to %d",
					this.currentCommunicationPoint);
			// Store the communication point for the execution in the DB.
			if (this.currentCommunicationPoint <= this.endPoint) {
				this.configManagerService.saveCommunicationPoint(this.executionId, this.currentCommunicationPoint);
			}
			else {
				LoggingHelper.debug().messageColor(Colors.ANSI_RED_BOLD).log("sendSyncMessage():: currentCommunicationPoint not increased due to warnings or errors (see above) ");
			}
		}

		syncMessage.setCommunicationPoint(this.currentCommunicationPoint);

        final List<String> warnings = new ArrayList<String>();
            this.blockContainerMap.values().forEach((final var blockContainer) -> {
            	LoggingHelper.debug().log("--------> WFS::sendSyncMessage():   Block %d:  %s ... CP=%d.  & setting " +
            			"Status (from %s) to WAITING    ==> for Phase: %s",
            			blockContainer.getIndex(), StringTemplates.EXECUTING_STEP_TACT, this.currentCommunicationPoint, blockContainer.getStatus(), simulationPhase);
            	/**
            	 * If blockContainer has the status SimulationStatus.READY, it is prepared for next simulation step (see nextStep())
            	 */
            	if( blockContainer.getStatus() == SimulationStatus.READY || blockContainer.getStatus() == SimulationStatus.INITIALIZED ) {
					this.statusHelper.setBlockStatus(blockContainer, SimulationStatus.WAITING);
					this.buildAndSendSyncMessage(syncProducer, blockContainer, simulationPhase, syncMessage);
				}
            	else {
            		warnings.add("sendSyncMessage():: SimulationStatus of BlockContainer '%d' is not READY (=> %s)".formatted(blockContainer.getIndex(), blockContainer.getStatus()));
            	}
            });

        warnings.forEach(w -> {
        	LoggingHelper.warn().messageColor(Colors.ANSI_RED_BOLD).log(w);
        });

    }

    private void buildAndSendSyncMessage(final MQSyncProducer syncProducer,
                                         final BlockContainer blockContainer,
                                         final SimulationPhase simulationPhase,
                                         final SyncMessage syncMessage) {
        syncMessage.setLocalBlockId(blockContainer.getIndex());
        syncMessage.setGlobalBlockId(blockContainer.getGlobalId());
        syncMessage.setCommunicationStepSize(blockContainer.getCommunicationStepSize(this.currentCommunicationPoint));
        syncMessage.setSimulationPhase(simulationPhase);

        LoggingHelper.debug().log("CP = %d ------------------- sending  %s  SYNC to Block %d --->",
                        this.currentCommunicationPoint, simulationPhase, blockContainer.getIndex());
        syncProducer.sendToQueue(NameHelper.getSyncQueueName(this.executionId, blockContainer.getGlobalId(),
                blockContainer.getIndex()), syncMessage);
    }


    /**
     * set all static inputs for the start of the blocks and return a list of {@link NotifyMessage}s for all blocks that do not have static inputs
     *
     * @param mqValueProducer     the producer that sends a value message
     *                            ({@link edu.kit.iai.webis.proofutils.message.ValueMessage}) to the queue
     *
     * @return List of {@link NotifyMessage}s for all blocks that have no static inputs
     */
    public List<NotifyMessage> setStaticInputs(MQValueProducer mqValueProducer) {
    	List<NotifyMessage> blockMessages = new ArrayList<NotifyMessage>(this.blockContainerMap.size());
    	final Map<String, String> executionParameters = this.execution.getExecParameters();

        this.blockContainerMap.values().forEach((final BlockContainer blockContainer) -> {
            final Map<String, String> blockStaticExecParameters = BlockHelper.getExecParameters(blockContainer.getBlock(), executionParameters);

            LoggingHelper.debug().messageColor(Colors.ANSI_GREEN_BOLD).log("Setting INIT values for Block %d", blockContainer.getIndex());
            if (blockStaticExecParameters.size() > 0) {
                // Sending INIT data
            	final ValueMessage valueMessage = (ValueMessage) MessageBuilder.init(MessageType.VALUE)
            			.localBlockId(blockContainer.getIndex())
            			.globalBlockId(blockContainer.getGlobalId())
            			.workflowId(this.workflow.getId())
            			.simulationPhase(SimulationPhase.INIT)
            			.communicationPoint(0)
            			.data(blockStaticExecParameters)
            			.build();
                final var staticInputsQueueName = NameHelper.getStaticInputsQueueName(this.executionId, blockContainer.getBlock());
                LoggingHelper.debug().messageColor(Colors.ANSI_RED).log("QUEUE-Name: " + staticInputsQueueName);
                mqValueProducer.sendToQueue(staticInputsQueueName, valueMessage);
                LoggingHelper.debug().messageColor(Colors.ANSI_RED).log("VALUE-Message sent: " + valueMessage);
                blockContainer.setStatus(SimulationStatus.WAITING);
            } else {
                LoggingHelper.warn().log("WFS:: There are no static inputs for block '" + blockContainer.getIndex() + "'!");
                final NotifyMessage notifyMessage = (NotifyMessage) MessageBuilder.init(MessageType.NOTIFY)
                		.localBlockId(blockContainer.getIndex())
                		.globalBlockId(blockContainer.getGlobalId())
                		.workflowId(this.workflow.getId())
                		.simulationPhase(SimulationPhase.INIT)
                		.communicationPoint(0)
                		.blockStatus(SimulationStatus.VALUES_SET)
                		.build();
                blockMessages.add(notifyMessage);
                return;
            }
        });
        return blockMessages;
    }

    /**
     * Start the Kubernetes pods
     * @param kubernetesService the proof kubernetes service
     */
    public void startKubernetesPods(KubernetesService kubernetesService) {
    	this.blockContainerMap.values().forEach((final BlockContainer blockContainer) -> {
    		if (kubernetesService != null) {
    			kubernetesService.startBlock(blockContainer, this.executionId);
    		}
    	});
    }


    /**
     * Abort all blocks (set the status type {@link SimulationStatus#ABORTED} and stop the blocks via the
     * {@link KubernetesService})
     *
     * @param kubernetesService the {@link KubernetesService} that starts the block on the cluster
     */
    public void abortBlocks(final KubernetesService kubernetesService) {
        this.blockContainerMap.values().parallelStream().forEach(blockContainer -> {
            blockContainer.setStatus(SimulationStatus.ABORTED);

            if (kubernetesService != null) {
                kubernetesService.stopBlock(blockContainer, this.executionId);
            }
        });
    }


    /**
     * create a {@link BlockContainer} with given arguments
     *
     * @param workflow      the {@link Workflow}
     * @param workflowBlock the (required) {@link WorkflowBlock}
     * @param block         the {@link Block}
     * @param executionId   the execution id
     * @return a {@link BlockContainer} instance
     */
    private void createBlockContainers() throws ElementCreationException {
        final Map<String, String> executionParameters = this.execution.getExecParameters();
        if (LoggingHelper.isLevelDebugOrTrace()) {
        	StringTemplates.printStarBordered(executionParameters.toString());
        }

        this.blockContainerMap.clear();

        final List<BlockContainer> blockContainers = this.workflow.getBlocks().values().parallelStream().map((final Block block) -> {
            try {
                if (BlockHelper.checkIfAllRequiredInputsHaveValues(block, executionParameters)) {
                    BlockContainer blockContainer = new BlockContainer();
                    blockContainer.setBlock(block);
                    blockContainer.setGlobalId(block.getId());
                    blockContainer.setWorkflowId(this.workflow.getId());
                    blockContainer.setIndex(block.getIndex());
                    blockContainer.setStatus(SimulationStatus.UNKNOWN);
                    blockContainer.setShutdownRelevance(block.isShutdownRelevant());
                    LoggingHelper.info().log("BLOCKCONTAINER '" + block.getId() + "' is "
                            + (blockContainer.isShutdownRelevant() ? "" : " NOT ") + "relevant for SHUTDOWN!");

                    blockContainer.setConfiguration(this.workflow.getStepBasedConfig());

                    /**
                     * REFACTOR: is this still necessary?
                     * The 'old' setting of the application-dev.yaml contents is done via autoWiring at the only location,
                     * where it is required:
                     * => prooforchestrator.config.OrchestrationConfig, used at prooforchestrator.service.KubernetesService
                     */
                    blockContainer.setEnvironmentVars(Map.ofEntries(
                    		Map.entry(StringTemplates.PROOF_WORKER_WORKFLOW_UUID_KEY, String.valueOf(this.workflow.getId())),
                            Map.entry(StringTemplates.PROOF_WORKER_WORKFLOW_EXECUTION_ID, String.valueOf(this.executionId)),
                            Map.entry(StringTemplates.PROOF_WORKER_BLOCK_UUID_KEY, String.valueOf(block.getId())),
                            Map.entry(StringTemplates.PROOF_WORKER_BLOCK_ID_KEY, String.valueOf(block.getIndex()))
                    ));
                    // add to the local blockContainer map:
                    this.blockContainerMap.put(blockContainer.getIndex(), blockContainer);
                    if (blockContainer.getEndPoint() > this.maxBlockEndPoint) {
                        this.maxBlockEndPoint = blockContainer.getEndPoint();
                    }
                    return blockContainer;

                } else {
                    throw new ElementCreationException("The value for the required static input of block '%s' (%s) is missing!".formatted(block.getName(), block.getId()));
                }
            } catch (Exception e) {
                throw new ElementCreationException(e.getMessage());
            }

        }).toList();
        List<BlockContainer> sortedContainers = new ArrayList<BlockContainer>();
        blockContainers.forEach(b -> sortedContainers.add(b));
        Collections.sort(sortedContainers, (a, b) -> a.getIndex() < b.getIndex() ? -1 : a.getIndex() == b.getIndex() ? 0 : 1);
        this.simulationStatus = SimulationStatus.UNKNOWN;
        LoggingHelper.trace().log("Block container list: " + sortedContainers);
        this.statusHelper.setBlockContainers(sortedContainers);

        try {
            this.connectInputsAndOutputs();
        } catch (SetupException e) {
            throw new ElementCreationException(e.getMessage());
        }

    }

    /**
     * create and set the {@link Input}s and {@link Output}s of the workflow container
     * based on the {@link BlockContainer}s and {@link Connection}s
     */
    public void connectInputsAndOutputs() throws SetupException {
        final HashMap<String, MQInPort> inPorts = new HashMap<>();
        final HashMap<String, MQOutPort> outPorts = new HashMap<>();
        final String workflowId = this.workflow.getId();
        // Extract Input and Output ports from blocks
        this.blockContainerMap.values().forEach((final var blockContainer) -> {
        	System.out.println("WFC::I/O von Block: " + blockContainer.getBlock().getName());
            if (blockContainer.getBlock().getInputs() != null) {
                blockContainer.getBlock().getInputs().values().forEach(input -> {
                    if (!(input.getCommunicationType().equals(CommunicationType.EVENT_STATIC)
                            || input.getCommunicationType().equals(CommunicationType.STEPBASED_STATIC))) {
                        final var portName =
                                NameHelper.getInputQueueName(this.executionId, workflowId,
                                        blockContainer.getIndex(), input.getName());
                        LoggingHelper.debug().log("WFC::connectInputsAndOutputs():  Inport-Name: " + portName);
                        inPorts.put(portName, new MQInPort(portName));
                    }
                });
            }
            else {
            	System.out.println("WFC::I/O von Block: " + blockContainer.getBlock().getName() + " => keine Inputs");
            }

            if (blockContainer.getBlock().getOutputs() != null) {
                blockContainer.getBlock().getOutputs().values().forEach(output -> {
                    final var portName =
                            NameHelper.getOutputQueueName(this.executionId, workflowId,
                                    blockContainer.getIndex(), output.getName());
                    LoggingHelper.debug().log("WFC::connectInputsAndOutputs():  Outport-Name: " + portName);
                    outPorts.put(portName, new MQOutPort(portName));
                });
            }
            else {
            	System.out.println("WFC::I/O von Block: " + blockContainer.getBlock().getName() + " => keine Outputs");
            }
        });
        
        // Connect Input and Output ports based on workflow config
        this.workflow.getConnections().forEach((final var connection) -> {
            final String inputBlockID = connection.getSourceBlockID();
            final String outputBlockID = connection.getTargetBlockID();
            final Block inputBlock = this.workflow.getBlock(inputBlockID);
            final Block outputBlock = this.workflow.getBlock(outputBlockID);
            final String inputName = outputBlock.getInputName(connection.getInputID());
            final String outputName = inputBlock.getOutputName(connection.getOutputID());

            final String outPortName = NameHelper.getOutputQueueName(
                    this.executionId,
                    workflowId,
                    inputBlock.getIndex(),
                    outputName);
            final String inPortName = NameHelper.getInputQueueName(
                    this.executionId,
                    workflowId,
                    outputBlock.getIndex(),
                    inputName);
            if (outPorts.get(outPortName) != null && inPorts.get(inPortName) != null) {
                outPorts.get(outPortName).addInPort(inPorts.get(inPortName));
            } else {
                LoggingHelper.error().log("OutPort '%s' %s found!", outPortName, outPorts.get(outPortName) == null ? "not" : "");
                LoggingHelper.error().log("InPort '%s' %s found!", inPortName, inPorts.get(inPortName) == null ? "not" : "");
            }
        });
        this.inPorts = new ArrayList<>(inPorts.values());
        this.outPorts = new ArrayList<>(outPorts.values());
    }

    public boolean canTerminateWorkflow(final String executionId) {
    	String msg = null;

    	if ( this.workflow == null || this.execution == null ) {
    		msg = "workflow not loaded, nothing to terminate ...";
    	}
    	else if ( this.executionId == null ) {
    		msg = "workflow execution id not loaded, nothing to terminate ...";
        }
        else if (! this.executionId.equalsIgnoreCase(executionId)) {
        	msg = "Wrong workflow execution id for terminating workflow;  current ID: " + this.executionId;
        }
        else if (
            this.simulationStatus == SimulationStatus.ABORTED || this.simulationStatus == SimulationStatus.SHUT_DOWN
            || this.simulationStatus == SimulationStatus.STOPPED )
        {
        	msg = "Workflow is already terminated, id=" + this.executionId;
        }

    	if( msg != null ) {
    		LoggingHelper.warn().log(LoggingHelper.printStarBordered(msg));
    		return false;
    	}
    	return true;
    };
}
