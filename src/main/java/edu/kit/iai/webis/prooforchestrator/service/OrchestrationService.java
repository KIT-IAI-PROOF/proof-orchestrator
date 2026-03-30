/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import java.util.NoSuchElementException;

import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.stereotype.Service;

import edu.kit.iai.webis.prooforchestrator.config.Configuration;
import edu.kit.iai.webis.prooforchestrator.config.OrchestrationConfig;
import edu.kit.iai.webis.prooforchestrator.exception.ConcurrentWorkflowException;
import edu.kit.iai.webis.prooforchestrator.exception.NotFoundException;
import edu.kit.iai.webis.prooforchestrator.io.MQNotifyHandler;
import edu.kit.iai.webis.prooforchestrator.util.DockerHelper;
import edu.kit.iai.webis.prooforchestrator.util.StatusHelper;
import edu.kit.iai.webis.prooforchestrator.util.StringTemplates;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.helper.NameHelper;
import edu.kit.iai.webis.proofutils.io.MQSyncProducer;
import edu.kit.iai.webis.proofutils.io.MQValueProducer;
import edu.kit.iai.webis.proofutils.message.MessageType;
import edu.kit.iai.webis.proofutils.message.NotifyMessage;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.model.ProcessEnvironment;
import edu.kit.iai.webis.proofutils.model.SimulationPhase;
import edu.kit.iai.webis.proofutils.service.ConfigManagerService;
import edu.kit.iai.webis.proofutils.service.ConsumerManager;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Execution;
import edu.kit.iai.webis.proofutils.wrapper.Workflow;

import static java.lang.Long.parseLong;

@Service
public class OrchestrationService {

    private final ScheduleService scheduleService;
    private final OrchestrationConfig orchestrationConfig;
    private final ConsumerManager consumerManager;
    private final AMQPService amqpService;
    private final MQValueProducer mqValueProducer;
    private final MQSyncProducer mqSyncProducer;
    private final StatusHelper statusHelper;
    private final ConfigManagerService configManagerService;
    private final WorkflowService workflowService;
    private final DockerHelper dockerHelper;
    private final ExecutionLoggingService executionLoggingService;
    //    private final RunConfiguration runConfiguration;
    private KubernetesService kubernetesService;
    private Execution execution;

    public OrchestrationService(final OrchestrationConfig orchestrationConfig,
                                final ScheduleService scheduleService,
                                final ConsumerManager consumerManager,
//                                final KubernetesService kubernetesService,
                                final AMQPService amqpService,
                                final MQValueProducer mqValueProducer,
                                final LocalProcessService localProcessService,
                                final MQSyncProducer mqSyncProducer,
                                final StatusHelper statusHelper,
                                final WorkflowService workflowService,
                                final DockerHelper dockerHelper,
                                final ConfigManagerService configManagerService,
                                final ExecutionLoggingService executionLoggingService) {
        this.orchestrationConfig = orchestrationConfig;
        this.scheduleService = scheduleService;
        this.consumerManager = consumerManager;
//        this.kubernetesService = kubernetesService;
        this.amqpService = amqpService;
        this.mqValueProducer = mqValueProducer;
        this.mqSyncProducer = mqSyncProducer;
        this.statusHelper = statusHelper;
        this.workflowService = workflowService;
        this.configManagerService = configManagerService;
        this.dockerHelper = dockerHelper;
        this.executionLoggingService = executionLoggingService;
        LoggingHelper.printColored(false);
    }

    /**
     * Prepare workflow before execution
     *
     * @param configuration Configuration object for orchestration params
     * @param executionId   UUID of the execution process for a workflow to get from config server
     */
    public Execution prepareWorkflow(final Configuration configuration, final String executionId) throws IllegalArgumentException {
        try {
        	this.execution = this.configManagerService.getExecution(executionId);
            SimulationStatus executionStatus = this.execution.getStatus();

            // REFACTOR: this is workaround, should be CREATED
            if(executionStatus != SimulationStatus.UNKNOWN
                && executionStatus != SimulationStatus.READY
                && executionStatus != SimulationStatus.SHUT_DOWN
                && executionStatus != SimulationStatus.STOPPED
                && executionStatus != SimulationStatus.ABORTED
                && executionStatus != SimulationStatus.FINALIZED
            ) {
                LoggingHelper.error().log(StringTemplates.AlREADY_RUNNING, executionId);
                throw new ConcurrentWorkflowException(StringTemplates.AlREADY_RUNNING.formatted(executionId));
            }
        	else {
                // Setup execution-specific logging
                this.executionLoggingService.setupExecutionLogging(executionId);
                
                final Workflow workflow = this.execution.getWorkflow();

                this.workflowService.initialize(this.execution);

                LoggingHelper.info().messageColor(Colors.ANSI_BLUE).log("Declaring DYNAMIC INPUTS Services:");
                this.amqpService.declareInputQueues(this.workflowService.getInPorts());

                LoggingHelper.info().messageColor(Colors.ANSI_BLUE).log("Declaring EXCHANGE Services:");
                this.amqpService.declareOutputExchanges(this.workflowService.getOutPorts());

                for (Block block : workflow.getBlocks().values()) {
                    LoggingHelper.info().messageColor(Colors.ANSI_BLUE)
                            .log("Declaring Services for block '%s' (%d)", block.getId(), block.getIndex());

                    this.amqpService.declareServiceQueue(NameHelper.getStaticInputsQueueName(executionId, block));
                    this.amqpService.declareServiceQueue(NameHelper.getSyncQueueName(executionId, block));
                    this.amqpService.declareServiceQueue(NameHelper.getNotifyQueueName(executionId, block));

                    this.consumerManager.instantiateReceiver(
                            NameHelper.getNotifyQueueName(executionId, block),
                            MessageType.NOTIFY,
                            new MessageListenerAdapter(
                                    new MQNotifyHandler(this),
                                    new Jackson2JsonMessageConverter())).start();
                }

                this.workflowService.setStaticInputsAndStartBlocks(
                        this.mqValueProducer,
                        this.orchestrationConfig,
                        this.kubernetesService);

                // Check override execution flag
                LoggingHelper.info().log(StringTemplates.PREPARED_WORKFLOW, workflow.getId());

//                if( this.orchestrationConfig.getProcessingEnvironment().equalsIgnoreCase("LOCAL")) {
                ProcessEnvironment processEnv = this.execution.getProcessEnvironment();
                switch (processEnv) {
                    case KUBERNETES -> {
                        try {
                            LoggingHelper.debug().log("Running in Kubernetes Cluster ... ");
                            this.kubernetesService = new KubernetesService(this.orchestrationConfig);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    case DOCKER -> {
                        try {
                            this.dockerHelper.processDockerExecution(workflow, executionId);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                this.configManagerService.saveExecutionStart(executionId);

                return this.execution;
            }
        } catch (final NoSuchElementException e) {
            LoggingHelper.error().log(StringTemplates.NO_WORKFLOW_CONFIG_AVAILABLE, executionId);
            throw new NotFoundException(StringTemplates.NO_WORKFLOW_CONFIG_AVAILABLE.formatted(executionId), e);
        }
    }

    /**
     * Start workflow execution using prepared workflow
     *
     * @param executionId Execution id of the prepared workflow
     */
    public void runWorkflow(final String executionId) {
        try {
//            final WorkflowContainer workflowContainer = this.workflowContainerSupplier.getWorkflowContainer();
            // Update status
            LoggingHelper.debug().workflowId(this.workflowService.getWorkflow().getName()).executionId(executionId)
                    .log(StringTemplates.STARTED_WORKFLOW, this.workflowService.getWorkflow().getName());
            // Check manual execution flag
//            if (!workflowContainer.getRuntimeOptions().isManual() && workflowContainer.isStartable()) {
            if (this.workflowService.isStartable()) {
                LoggingHelper.trace().log("WFC is startable, scheduling  WFC !");
                this.workflowService.prepareNextStepForAllBlocks();
                this.scheduleService.schedule(this.workflowService);
				this.workflowService.setSimulationStatus(SimulationStatus.READY);
            }
//            workflowContainer.setStatus(SimulationStatus.CREATED); now in setBlockStatus(s)
            // save to db for logging and reuse
//            this.workflowContainerSupplier.saveToDatabase();
        } catch (final NoSuchElementException e) {
            final String error = StringTemplates.NO_WORKFLOWCONTAINER_KNOWN.formatted(executionId);
            LoggingHelper.error().log(error);
            throw new NotFoundException(error, e);
        }
    }

    /**
     * set the status of a block that is coming from the worker via {@link MQNotifyHandler}
     *
     * @param notifyMessage the message from the worker
     */
    public synchronized void setBlockStatus(NotifyMessage notifyMessage) {
        final Integer blockId = notifyMessage.getLocalBlockId();
        final SimulationStatus blockStatus = notifyMessage.getBlockStatus();
        boolean shuttingDown = false;

        try {
            LoggingHelper.trace()
                    .workflowId(this.workflowService.getWorkflow().getId())
                    .localBlockId(blockId)
                    .log("Setting block status to >> %s << for block >> %s <<", blockStatus,
                            notifyMessage.getGlobalBlockId());

            // avoid multiple shutdown sync messages
            if( blockStatus == SimulationStatus.SHUT_DOWN && this.statusHelper.areAllStatus(SimulationStatus.SHUT_DOWN)) {
            	return;
            }

            this.statusHelper.setBlockStatus(blockId, blockStatus);

            if (this.statusHelper.existsErrorSimulationStatus()) {
                LoggingHelper.printStarBordered(notifyMessage.getErrorText());
            }


            // Only proceed if the status is CREATED or SHUT_DOWN
            if (blockStatus != SimulationStatus.CREATED
                    && blockStatus != SimulationStatus.SHUT_DOWN
            ) {
                return;
            }

            switch (blockStatus) {
				case CREATED -> {
					if (this.statusHelper.areAllStatus(SimulationStatus.CREATED))
					{
						LoggingHelper.info().log(LoggingHelper.printStarBordered("All Blocks are CREATED, Running Workflow"));
						this.runWorkflow(notifyMessage.getExecutionId());
						this.workflowService.setSimulationStatus(SimulationStatus.CREATED);
					}
				}
				case SHUT_DOWN -> {
					if( shuttingDown ) {
						LoggingHelper.warn().log("Workflow is already shutting down, status message SHUT_DOWN ignored");
					}
                    LoggingHelper.debug().log("=== Case SHUT_DOWN, execution '%s': all SHUT_DOWN: %s",
                        notifyMessage.getExecutionId(), this.statusHelper.areAllStatus(SimulationStatus.SHUT_DOWN));

		            if ( this.statusHelper.areAllStatus(SimulationStatus.SHUT_DOWN) )
		            {
                        // Stop the execution specific logging
                        String executionId = notifyMessage.getExecutionId();
                        LoggingHelper.debug().log("=== Shutting down execution '%s'", executionId);

		            	shuttingDown = true;
		                this.abortWorkflow(notifyMessage.getExecutionId(), SimulationStatus.SHUT_DOWN);
                        LoggingHelper.debug().log("=== Resetting status for execution '%s'", executionId);
						this.workflowService.setSimulationStatus(SimulationStatus.SHUT_DOWN);
		            }
				}
				default -> {} // do nothing, status is already set
			}
// delete, because ScheduleService does the rest
//            switch (this.workflowService.getWorkflow().getSimulationStrategy()) {
//                case WAIT_AND_CONTINUE -> {
////                	if (workflowContainer.haveAllBlockContainersSameStatus(SimulationStatus.EXECUTION_STEP_FINISHED)) {
////YYYYY                    if (workflowContainer.haveAllBlockContainersSameStatus(SimulationStatus.READY)) {
////                        // communication points increase for all blocks
////                        workflowContainer.prepareNextStepForAllBlocks();
////                    }
////	                else if (workflowContainer.haveShutdownRelevantBlocksFinalized()){
////	                }
//                }
//                case IGNORE -> {
//                    // must be updated to new Stucture!!
//                    if (notifyMessage.getSimulationPhase() == SimulationPhase.EXECUTE
//                            && status == SimulationStatus.EXECUTION_STEP_FINISHED) {
//                        //RL Bei Ignore darf nur der Orch den nächsten Step anstossen.
////					workflowContainer.nextStepForAllBlocks();
//                        this.workflowService.prepareNextStep(blockId);
//                    }
//                }
//                default -> throw new IllegalArgumentException("Unexpected value: " + this.workflowService.getWorkflow().getSimulationStrategy());
//            }


//PERF            this.workflowContainerRepository.save(workflowContainer);
        } catch (final NoSuchElementException e) {
            LoggingHelper.error().log(StringTemplates.NO_WORKFLOWCONTAINER_KNOWN, notifyMessage.getExecutionId());
            throw new NotFoundException(StringTemplates.NO_WORKFLOWCONTAINER_KNOWN.formatted(notifyMessage.getExecutionId()), e);
        }
    }


    public void abortWorkflow(final String executionId, SimulationStatus finalSimulationStatus) {
        LoggingHelper.info().messageColor(Colors.ANSI_YELLOW)
        .log("Try to stop and terminate workflow '%s'", executionId);

        try {
        	if( ! this.workflowService.canTerminateWorkflow(executionId) ) {
        		return;
        	}
        	this.workflowService.setSimulationStatus(finalSimulationStatus);
            this.workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.SHUTDOWN);

            Long duration = this.workflowService.getStepBasedDuration();
            Long shutdownTimeout_ms = parseLong(this.orchestrationConfig.getShutdownTimeout())*1000;

            LoggingHelper.info().log("OrchestrationService:  shutting down all Blocks,   ... waiting %d ms", (duration + shutdownTimeout_ms));
            Thread.currentThread().join(duration + shutdownTimeout_ms);
            LoggingHelper.info().log("OrchestrationService:  shutting down ... ");

            this.consumerManager.stopConsumers();
            this.scheduleService.stop();
            // Check dry run and delete container
            if (this.execution.getProcessEnvironment() == ProcessEnvironment.KUBERNETES) {
                this.workflowService.abortBlocks(this.kubernetesService);
            }

            this.amqpService.deleteOutputExchanges(this.workflowService.getOutPorts());
            this.amqpService.deleteInputQueues(this.workflowService.getInPorts());

            for (Block block : this.workflowService.getWorkflow().getBlocks().values()) {

                String queueName = NameHelper.getStaticInputsQueueName(executionId, block);
                if (this.amqpService.deleteServiceQueue(queueName)) {
                    LoggingHelper.info().log("STATIC INPUTS Queue '%s' deleted", queueName);
                } else {
                    LoggingHelper.error().log("STATIC INPUTS Queue '%s' could not be deleted!", queueName);
                }

                queueName = NameHelper.getSyncQueueName(executionId, block);

                if (this.amqpService.deleteServiceQueue(queueName)) {
                    LoggingHelper.info().log("Sync Queue '%s' deleted", queueName);
                } else {
                    LoggingHelper.error().log("Sync Queue '%s' could not be deleted", queueName);
                }

                queueName = NameHelper.getNotifyQueueName(executionId, block);
                if (this.amqpService.deleteServiceQueue(queueName)) {
                    LoggingHelper.info().log("Notify Queue '%s' deleted", queueName);
                } else {
                    LoggingHelper.error().log("Notify Queue '%s' could not be deleted", queueName);
                }

            }

            LoggingHelper.info()
                .log(finalSimulationStatus == SimulationStatus.ABORTED ? StringTemplates.WORKFLOW_WAS_ABORTED :
                        StringTemplates.WORKFLOW_WAS_SHUTDOWN);

            LoggingHelper.debug().log("OrchestrationService: Setting final status for execution '%s'", executionId);
            // Remove execution-specific logging
            //this.executionLoggingService.removeExecutionLogging(executionId);

            // Update status
        	this.workflowService.setSimulationStatus(finalSimulationStatus);

            // Stop the execution specific logging
            LoggingHelper.debug().log("OrchestrationService: Stopping execution-specific logging for execution '%s'", executionId);
            this.executionLoggingService.stopExecutionLogging(executionId);

        } catch (InterruptedException e) {
            LoggingHelper.error().printStackTrace(e).log("Error shutting down all Blocks!");
        }
    }

}
