/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import edu.kit.iai.webis.proofutils.model.SimulationStrategy;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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
import edu.kit.iai.webis.proofutils.model.ProcessEnvironment;
import edu.kit.iai.webis.proofutils.model.SimulationPhase;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.service.ConfigManagerService;
import edu.kit.iai.webis.proofutils.service.ConsumerManager;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Execution;
import edu.kit.iai.webis.proofutils.wrapper.Workflow;

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
    private final ExecutionUpdateQueueService executionUpdateQueueService;
    private final WebClient webClient;
    private KubernetesService kubernetesService;
    private Execution execution;
    // Dedicated single-thread executor used to run the terminal workflow shutdown
    // (see setBlockStatus() case SHUT_DOWN) off the RabbitMQ listener-container consumer
    // thread that received the triggering notify message.
    private final ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "workflow-shutdown"));

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
                                final ExecutionLoggingService executionLoggingService,
                                final ExecutionUpdateQueueService executionUpdateQueueService,
                                final WebClient defaultWebClient) {
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
        this.executionUpdateQueueService = executionUpdateQueueService;
        this.webClient = defaultWebClient;
        LoggingHelper.printColored(false);
    }

    /**
     * Prepare workflow before execution
     *
     * @param executionId   UUID of the execution process for a workflow to get from config server
     */
    public Execution prepareWorkflow(final String executionId) throws IllegalArgumentException {
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
                
                // Create execution-specific status and value queues
                this.executionUpdateQueueService.createStatusQueue(executionId);
                this.executionUpdateQueueService.createValueQueue(executionId);

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

//                this.workflowService.setStaticInputsAndStartBlocks(
//                        this.mqValueProducer,
//                        this.orchestrationConfig,
//                        this.kubernetesService);   // TODO: instantiate kubernetesService for V2
//
//                // Check override execution flag
//                LoggingHelper.info().log(StringTemplates.PREPARED_WORKFLOW, workflow.getId());

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
                            LoggingHelper.error().log("ERROR processing docker! " + e.getMessage());
                            this.abortWorkflow(executionId);  // @TODO:  BS.STOPPED oder ABORTED besser?
                            return this.execution;
                        }
                    }
                }

                // Check override execution flag
                LoggingHelper.info().log(StringTemplates.PREPARED_WORKFLOW, workflow.getId());
                this.configManagerService.saveExecutionStart(executionId);

                return this.execution;
            }
        } catch (final NoSuchElementException e) {
            LoggingHelper.error().log(StringTemplates.NO_WORKFLOW_CONFIG_AVAILABLE, executionId);
            throw new NotFoundException(StringTemplates.NO_WORKFLOW_CONFIG_AVAILABLE.formatted(executionId), e);
        }
    }


    private void initializeWorkflow(final String executionId) {
    	List<NotifyMessage> blockMessages = this.workflowService.setStaticInputs(this.mqValueProducer);
    	// set block statuses for all blocks that have no static inputs
    	blockMessages.forEach(bm -> this.setBlockStatus(bm));
    }


    /**
     * Start workflow execution using prepared workflow after having received NotifyMessage CREATED from all Blocks
     * (see {@link #setBlockStatus(NotifyMessage)}
     *
     * @param executionId Execution id of the prepared workflow
     */
    private void runWorkflow(final String executionId) {

        try {
            LoggingHelper.debug().workflowId(this.workflowService.getWorkflow().getName())
                    .log(StringTemplates.STARTED_WORKFLOW, this.workflowService.getWorkflow().getName());

            while( ! this.workflowService.isStartable() ) {
            	try {
            		System.out.println("WF-Service: not yet startable, waiting for 500ms ...");
            		Thread.sleep(500);
            	} catch (InterruptedException e) {
            		e.printStackTrace();
            		break;
            	}
            }
            LoggingHelper.debug().log("WFC is startable, scheduling  WFC !");
            this.workflowService.prepareNextStepForAllBlocks(); // prepareInitStep oder prepareFirstStep ??? Problem: erster Step ohne Values für Blöcke der 2. Stufe
            this.scheduleService.schedule(this.workflowService);
            this.workflowService.setSimulationStatus(SimulationStatus.READY);  // TODO::?? => WAITING setzen, bis alle Worker sich mit READY zurückgemeldet haben

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
     *                      TODO:  use StatusHelper ?
     */
    public synchronized void setBlockStatus(NotifyMessage notifyMessage) {
        final Integer blockId = notifyMessage.getLocalBlockId();
        final SimulationStatus blockStatus = notifyMessage.getBlockStatus();
        boolean shuttingDown = false;

        try {
            LoggingHelper.debug()
                    .workflowId(this.workflowService.getWorkflow().getId())
                    .localBlockId(blockId)
                    .log("Setting block status to >> %s << for block >> %s << (%d)", blockStatus,
                            notifyMessage.getGlobalBlockId(), notifyMessage.getLocalBlockId());

            // avoid multiple shutdown sync messages
            if( blockStatus == SimulationStatus.SHUT_DOWN && this.statusHelper.areAllStatus(SimulationStatus.SHUT_DOWN)) {
            	return;
            }

            this.statusHelper.setBlockStatus(blockId, blockStatus);
            
            if (this.statusHelper.existsErrorSimulationStatus()
            		&& this.statusHelper.isError(blockStatus) )
            {
            	final String errTxt = notifyMessage.getErrorText();
                LoggingHelper.printStarBordered( errTxt == null || errTxt.isBlank() ? "no error text available!" : errTxt);
                LoggingHelper.printStarBordered( "Workfow will be stopped and shut down ..." );
                workflowService.sendShutdownMessages(mqSyncProducer);
                return;
            }

            switch (blockStatus) {
            	case CREATED -> {
					if (this.statusHelper.areAllStatus(SimulationStatus.CREATED) ) {
						this.initializeWorkflow(notifyMessage.getExecutionId());
						this.workflowService.setSimulationStatus(SimulationStatus.CREATED);
					}
            	}
				case VALUES_SET -> {
					if (this.statusHelper.areAllStatus(SimulationStatus.VALUES_SET)
							&& this.workflowService.getSimulationStatus() == SimulationStatus.CREATED)
					{
						LoggingHelper.info().log(LoggingHelper.printStarBordered("All Blocks got their INIT values, try to start Workflow with Phase INIT"));
						this.runWorkflow(notifyMessage.getExecutionId());
					}
				}
				//@TODO:  what about a WF that has only blocks that are not shutDownRelevan?
				case EXECUTION_FINISHED -> {
					if (this.statusHelper.hasAShutdownRelevantBlockTheStatus(SimulationStatus.EXECUTION_FINISHED) ) {
						this.workflowService.sendFinalizeMessages(mqSyncProducer);
						this.workflowService.setSimulationStatus(SimulationStatus.FINALIZED);
					}
				}
				case FINALIZED -> {
					if (this.statusHelper.areAllStatus(SimulationStatus.FINALIZED) ) {
						this.workflowService.sendShutdownMessages(mqSyncProducer);
						this.workflowService.setSimulationStatus(SimulationStatus.FINALIZED);
					}
				}
				case SHUT_DOWN -> {
//					if( shuttingDown ) {
//						LoggingHelper.warn().log("Workflow is already shutting down, status message SHUT_DOWN ignored");
//					}
		            if ( this.statusHelper.areAllStatus(SimulationStatus.SHUT_DOWN) )
		            {
                        // Stop the execution specific logging
                        String executionId = notifyMessage.getExecutionId();
                        LoggingHelper.debug().log("=== Shutting down execution '%s'", executionId);
                        this.workflowService.setSimulationStatus(SimulationStatus.SHUT_DOWN);

		            	shuttingDown = true;
		                // Dispatch the actual cleanup (queue deletion, consumer shutdown, master
		                // callback) to a dedicated worker thread instead of running it inline on
		                // this RabbitMQ listener-container consumer thread. shutdownWorkflow() calls
		                // ConsumerManager.stopConsumers(), which would otherwise try to stop/join the
		                // very same container thread that is currently executing this handler,
		                // causing a self-join deadlock that is only resolved by the container's
		                // shutdown timeout (see "Closing channel for unresponsive consumer" warnings)
		                // and leaves the thread's interrupted flag set for unrelated subsequent work.
		                this.shutdownExecutor.execute(() -> {
		                    try {
		                        this.stopWorkflow(executionId, SimulationStatus.SHUT_DOWN);
		                        LoggingHelper.debug().log("=== Shutdown completed ");
		                    } catch (final Exception e) {
		                        LoggingHelper.error().log("Error while shutting down execution '%s': %s", executionId, e.getMessage());
		                    }
//		                    finally {
//		                        this.workflowService.setSimulationStatus(SimulationStatus.SHUT_DOWN);
//		                    }
		                });
		            }
				}
				default -> {} // do nothing, status is already set
			}
        } catch (final NoSuchElementException e) {
            LoggingHelper.error().log(StringTemplates.NO_WORKFLOWCONTAINER_KNOWN, notifyMessage.getExecutionId());
            throw new NotFoundException(StringTemplates.NO_WORKFLOWCONTAINER_KNOWN.formatted(notifyMessage.getExecutionId()), e);
        }
    }

    /**
     * this method is called when all blocks notified {@link  SimulationStatus#SHUT_DOWN}. This means that the blocks ended their process.
     * Now the connections must be unmounted and deleted.
     * @param executionId the id of the running execution
     */
    public void stopWorkflow(final String executionId, SimulationStatus finalSimulationStatus) {
        LoggingHelper.info().messageColor(Colors.ANSI_GREEN_BOLD).log("stopping workflow '%s'", executionId);

        LoggingHelper.debug().log("Setting final status %s for execution '%s'", finalSimulationStatus, executionId);
        
        Long duration = this.workflowService.getStepBasedDuration();
        Long shutdownTimeout_ms = this.orchestrationConfig.getShutdownTimeout()*1000;
        this.notifyMasterExecutionFinished(executionId);

        switch (finalSimulationStatus) {
		case ABORTED, SHUT_DOWN -> {
	        // @TODO: necessary here, because all blocks are already shut down?
	        try {
	        	Thread.sleep(duration + shutdownTimeout_ms);
	        } catch (InterruptedException e) {
	        	// Intentionally NOT restoring the interrupt flag here: this thread continues to
	        	// perform further blocking work below (queue cleanup, WebClient.block() call to
	        	// notify proof-master). If the interrupt flag stayed set, those subsequent
	        	// blocking calls would immediately fail with an InterruptedException too.
	        	LoggingHelper.warn().log("Shutdown wait interrupted early, continuing cleanup");
	        } 
	        
	        // @TODO: useful here or better somehow in DockerHelper??
	        if (this.execution.getProcessEnvironment() == ProcessEnvironment.KUBERNETES) {
	        	this.workflowService.abortBlocks(this.kubernetesService);
	        }

	        // deleting Queues and Exchanges
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
	        
	    	this.consumerManager.stopConsumers();
	    	this.amqpService.deleteOutputExchanges(this.workflowService.getOutPorts());
	    	this.amqpService.deleteInputQueues(this.workflowService.getInPorts());

    		new Thread(this.scheduleService::stop).start();
    		
    		LoggingHelper.info().log("Workflow with execution id '%s' was shut down", executionId );

            // Stop the execution-specific logging only now that all cleanup logging above has been emitted
            this.executionLoggingService.stopExecutionLogging(executionId);
		}
		case STOPPED -> {
    		LoggingHelper.info().log("Workflow with execution id '%s' was stopped", executionId );
		}
		case SUSPENDED -> {
    		LoggingHelper.info().log("Workflow with execution id '%s' was suspended", executionId );
		}
		default ->
			throw new IllegalArgumentException("Unexpected value: " + finalSimulationStatus);
		}
    }


    /**
     * abort the running workflow while sending a shutdown sync to all blocks. The reply of the blocks will we managed by the 
     * {@link ScheduleService}
     * @param executionId the current execution id
     */
    public void abortWorkflow(final String executionId) {
    	LoggingHelper.info().messageColor(Colors.ANSI_CYAN_BOLD)
    	.log("Try to stop and terminate workflow '%s'", executionId);
    	
    	if( ! this.workflowService.canTerminateWorkflow(executionId) ) {
    		return;
    	}
    	this.workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.SHUTDOWN);
    }
    
    /**
     * Notify proof-master that this orchestrator instance has finished its workflow (all its
     * workers have shut down) so that proof-master can stop and remove this orchestrator's now
     * unneeded container. Best-effort: any failure is logged but does not affect the workflow's
     * own shutdown, e.g. when the orchestrator is run standalone without a configured master.
     *
     * @param executionId UUID of the execution that just finished
     */
    private void notifyMasterExecutionFinished(final String executionId) {
    	
    	if( this.orchestrationConfig.isRunAsMaster() ) {
        	LoggingHelper.debug().log("Execution '%s' finished (without master). ", executionId);
        	return;
    	}
    	
    	LoggingHelper.debug().log("Notifying proof-master that execution '%s' finished. MasterCallbackURL: %s", executionId, this.orchestrationConfig.getMasterCallbackUrl().toString());
    	
    	
        this.orchestrationConfig.getMasterCallbackUrl().ifPresentOrElse(callbackUrl -> {
            try {
                final var uri = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(callbackUrl)
                        .queryParam("executionId", executionId)
                        .build()
                        .toUri();
                LoggingHelper.debug().log("==>  Notifying proof-master that execution '%s' finished. generated URI: %s", executionId, uri.toString());
                this.webClient.post()
                        .uri(uri)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                LoggingHelper.info().log("==>  Notified proof-master that execution '%s' finished", executionId);
            } catch (Exception e) {
                LoggingHelper.warn().log("Could not notify proof-master that execution '%s' finished: %s",
                        executionId, e.getMessage());
            }
        }, () -> LoggingHelper.debug().log(
                "No proof-master callback URL configured, skipping finished-notification for execution '%s'", executionId));
    }

    /**
     * Cleanly shut down the dedicated shutdown-worker thread when this bean is destroyed
     * (e.g. on application context shutdown), so no non-daemon thread keeps the JVM alive.
     */
    @PreDestroy
    private void shutdownShutdownExecutor() {
        this.shutdownExecutor.shutdown();
        try {
            if (!this.shutdownExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.shutdownExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.shutdownExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
