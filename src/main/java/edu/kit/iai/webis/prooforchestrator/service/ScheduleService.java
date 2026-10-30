/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import edu.kit.iai.webis.prooforchestrator.config.OrchestrationConfig;
import edu.kit.iai.webis.prooforchestrator.exception.ScheduleException;
import edu.kit.iai.webis.prooforchestrator.util.StatusHelper;
import edu.kit.iai.webis.prooforchestrator.util.StringTemplates;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.io.MQSyncProducer;
import edu.kit.iai.webis.proofutils.model.SimulationPhase;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.model.SimulationStrategy;


@Service
public class ScheduleService {

    private final MQSyncProducer mqSyncProducer;
    private final StatusHelper statusHelper;
    private ScheduledExecutorService executor = null;
    private Long stepDuration = 200L;
    // Delay to be used to schedule createCheckTask when shutting down
    private final Integer SHUTDOWN_DELAY = 1000;   // [ms]
    private Long workerTimeout_ms = 10000L;
    private Long elapsedTime_ms = 0L;
    
    public ScheduleService(final MQSyncProducer mqSyncProducer,
                           final StatusHelper statusHelper,
                           final OrchestrationConfig orchestrationConfig) {
        this.mqSyncProducer = mqSyncProducer;
        this.statusHelper = statusHelper;
    }

    /**
     * Start workflow scheduling
     *
     * @param workflowService Workflow to schedule
     */
    public void schedule(final WorkflowService workflowService) {
        this.stepDuration = workflowService.getStepBasedDuration();
        this.workerTimeout_ms = workflowService.getWorkflow().getStepBasedConfig().getWorkerTimeout() * 1000;
        
        LoggingHelper.info().log("Scheduling workflow %s with step duration = %d ms and worker timeout = %d ms", 
        		workflowService.getWorkflow().getName(), this.stepDuration, this.workerTimeout_ms);
        if (this.stepDuration > 0) {
            // Start the executor for step-based scheduling
            this.executor = Executors.newSingleThreadScheduledExecutor();
            final Runnable checkTask = this.createCheckTask(this.executor, workflowService);
            final Runnable stepTask = this.createStepTask(this.executor, workflowService, checkTask);
            final Runnable initTask = this.createInitTask(this.executor, workflowService, stepTask);
            this.executor.schedule(initTask, workflowService.getStepBasedStartTime(), TimeUnit.MILLISECONDS);
        } else {
            // Start the executor for event-based scheduling
        }

    }

    /**
     * Stop the executor
     */
    public void stop() {
        if (this.executor != null) {
            try {
            	LoggingHelper.info().log("Stopping all Threads: ");
            	List<Runnable> threadList = this.executor.shutdownNow();
            	threadList.forEach(t -> System.out.println(t));
            	LoggingHelper.info().log("waiting for all Threads to be terminated ...");
				boolean retCode = this.executor.awaitTermination(3L, TimeUnit.SECONDS);
				if( retCode ) {
					LoggingHelper.info().log("All Threads shut down successfully");
				}
				else {
					LoggingHelper.warn().log("shut down of all Threads not ended, elapsed waiting time was 3 seconds.");
				}
			} catch (InterruptedException e) {
				LoggingHelper.warn().log("Executor termination was interrupted, reason: %s", e.getMessage());
				Thread.currentThread().interrupt();
			}
        }
    }

    /**
     * the check task sends sync steps based on the workflow's {@link AsyncAction}
     *
     * @param executor
     * @param workflowService
     * @return
     */
    private Runnable createCheckTask(final ScheduledExecutorService executor,
                                     final WorkflowService workflowService) {
        return new Runnable() {
            @Override
            public void run() {
            	boolean shuttingDown = false;

                if( LoggingHelper.isLevelDebugOrTrace() ) {
                	ScheduleService.this.statusHelper.printBlockStatuses();
                }
                try {
                    if (ScheduleService.this.statusHelper.existsErrorSimulationStatus()) {
                        LoggingHelper.error().workflowId(workflowService.getWorkflow().getId())
                                .log("Error block status is found! NO more SyncMessage will be sent! => sending SHUTDOWN to all blocks");
                    }
                    // if all blocks are shut down (see OrchestrationService.setBlockStatus(*)) stop all Tasks
                    if( workflowService.getSimulationStatus() == SimulationStatus.SHUT_DOWN ) {
                    	LoggingHelper.info().messageColor(Colors.ANSI_BLUE_BOLD).log("---> workflow is shut down => stopping all still running tasks ...");
                    	new Thread(ScheduleService.this::stop).start();
                    	LoggingHelper.info().messageColor(Colors.ANSI_BLUE_BOLD).log("---> done");
                    	return;
                    }
                    else if ( ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.EXECUTION_FINISHED)
                    		&&  
                    		!  ScheduleService.this.statusHelper.hasAShutdownRelevantBlockTheStatus(SimulationStatus.EXECUTION_FINISHED )) 
                    {
                    		LoggingHelper.info().log("One or more blocks that are not relevant for a workflow shutdown has finished their execution");
                    		LoggingHelper.info().log(ScheduleService.this.statusHelper.getListOfBlocksWithStatus(SimulationStatus.EXECUTION_FINISHED)
                    									+ ", continuing the simulation ... ");
                    		workflowService.prepareNextStepForAllBlocks();
                    		workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.EXECUTE);
                    }
                    else if( ScheduleService.this.elapsedTime_ms > ScheduleService.this.workerTimeout_ms
                    		&& !shuttingDown ) 
                    {
                        LoggingHelper.info().log("worker timeout of %d ms exceeded! finalizing and shutting down ... ", 
                        		ScheduleService.this.workerTimeout_ms);
                        LoggingHelper.printStarBordered("worker timeout of " + ScheduleService.this.workerTimeout_ms + " ms exceeded! finalizing and shutting down ... ");

                        workflowService.sendFinalizeMessages(mqSyncProducer);
                        shuttingDown = true;
                    }
                    else if( !shuttingDown ){
                        // All blocks have the status EXECUTION_STEP_FINISHED, READY or INITIALIZED
                    	try {
                    		switch (workflowService.getWorkflow().getSimulationStrategy()) {
	                    		case WAIT_AND_CONTINUE -> {
	                    			
	                    			if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.EXECUTION_STEP_FINISHED)
	                    					|| ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY)
	                    					|| ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.INITIALIZED)
	                    					) {
	                    				// set the status to READY
	                    				workflowService.prepareNextStepForAllBlocks();
	                            		workflowService.sendSyncMessage( ScheduleService.this.mqSyncProducer, SimulationPhase.EXECUTE);
	                            		ScheduleService.this.elapsedTime_ms = 0L;
	                    			}
	                    		}
	                    		case WAIT_AND_RETRY -> {
	                    			System.out.println("\nSS::  ============>>>>> ScheduleService: WAR  <<<<<========== \n");
	                    			
	                    			if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.EXECUTION_STEP_FINISHED)
	                    					|| ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY)
	                    					|| ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.INITIALIZED)
	                    					) {
	                    				// set the status to READY for all blocks
	                    				System.out.println("\nSS::  ============>>>>> next step WAR <<<<<========== \n");
	                    				workflowService.prepareNextStepForAllBlocks();
	                    			}
	                    			else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.RETRY)) {
                                        // prepare all affected blocks for the retry step
	                    				System.out.println("\nSS::  any status is RETRY:  ============>>>>> next step WAR : prepare RETRY Step <<<<<========== \n");
	                    				workflowService.prepareRetryStep();
	                    			}
	                    			else {
                                        System.out.println("\nSS::  no status is RETRY:  ============>>>>> ELSE <<<<<==========");
	                    				ScheduleService.this.statusHelper.printBlockStatuses();
	                    				throw new ScheduleException(": => no status is RETRY:  ============>>>>> ELSE <<<<<==========");
	                    			}
                                    workflowService.sendSyncMessage( ScheduleService.this.mqSyncProducer, SimulationPhase.EXECUTE);
                                    ScheduleService.this.elapsedTime_ms = 0L;
	                    		}
	                    		case IGNORE -> {
	                        		workflowService.sendSyncMessage( ScheduleService.this.mqSyncProducer, SimulationPhase.EXECUTE);
	                    		}
	                    		default -> {
	                    			throw new IllegalArgumentException("Unexpected value: " + workflowService.getWorkflow().getSimulationStrategy());
	                    		}
                    		} // switch()
							
                    	} catch (ScheduleException e) {
                            LoggingHelper.error().log(StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW, e.getMessage());
						}
                    } //else

                } catch (final Exception e) {
                    LoggingHelper.error().log(StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW, e.getMessage());
                    new ScheduleException(StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW, e).printStackTrace();
                }
                final Long stepSize = Long.valueOf(1);

                ScheduleService.this.elapsedTime_ms += ScheduleService.this.stepDuration;
                LoggingHelper.debug().log("elapsed time: %d", ScheduleService.this.elapsedTime_ms);
                executor.schedule(this, shuttingDown ? SHUTDOWN_DELAY : ScheduleService.this.stepDuration * stepSize, TimeUnit.MILLISECONDS);
            }
        };
    }



    private Runnable createStepTask(final ScheduledExecutorService executor,
                                    final WorkflowService workflowService,
                                    final Runnable checkTask) {
        return () -> {
            final String methodName = "Starting STEP-Task::";
            LoggingHelper.trace().log(methodName);
            try {
                // for all cases:
                if (ScheduleService.this.statusHelper.isAnyBlockContainerTerminated()) {
                    LoggingHelper.info().log("Any block container is terminated! NO more SyncMessage!");
                    LoggingHelper.printStarBordered(StringTemplates.ABORTED_STOPPING_SCHEDULING);
                	ScheduleService.this.stop();
                } else if (this.statusHelper.existsErrorSimulationStatus()) {
                    LoggingHelper.error().log("Error block status is found! NO more SyncMessage!");
                } else {
                    switch (workflowService.getWorkflow().getSimulationStrategy()) {
                        case WAIT_AND_CONTINUE, WAIT_AND_RETRY -> {

                            if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY)
                                    || ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.INITIALIZED)) {
                                LoggingHelper.info().log(StringTemplates.ALL_BLOCKS_COMPLETE_DOING_STEP);
                                workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.EXECUTE);
                            } 
                            else {// Skip step and wait until all blocks finish their tasks and send back notify messages
                                LoggingHelper.info().log(methodName
                                        + StringTemplates.BLOCKS_INCOMPLETE_WAITING);
                                LoggingHelper.info().log(methodName
                                        + ": Skip step and wait until all blocks finish "
                                        + "their tasks and send back notify messages");
                            }
                        }
                        case IGNORE -> {
//                            System.out.println(
//                                    "++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ IGNORE " +
//                                            "++++++++++++++ passed INIT? +++++++++++++++++++++++:  "
//                                            + this.statusHelper.hasSimulationPassedPhase(SimulationPhase.INIT));
                            if (this.statusHelper.hasSimulationPassedPhase(SimulationPhase.INIT)) {
                                LoggingHelper.debug().log(methodName + "->");
                                workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.EXECUTE);
                            } else {
                                LoggingHelper.debug().log(methodName + "-> Not all Blocks have passed INIT Phase");
                            }
                        }
                        default -> throw new IllegalArgumentException("Unexpected value: "
                                + workflowService.getWorkflow().getSimulationStrategy());
                    }

                }
            } catch (final Exception e) {
                final var message = StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW;
                LoggingHelper.error().log(message, e.getMessage());
                new ScheduleException(message, e).printStackTrace();
            }
            final Long stepSize = Long.valueOf(1);

            executor.schedule(checkTask, this.stepDuration * stepSize, TimeUnit.MILLISECONDS);
        };
    }

    private Runnable createInitTask(final ScheduledExecutorService executor,
                                    final WorkflowService workflowService,
                                    final Runnable stepTask) {
        final String methodName = "Starting INIT-Task::";
        return () -> {
            try {

                LoggingHelper.info().log(methodName + StringTemplates.EXECUTING_INIT_TACT);
                workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.INIT);
            } catch (final Exception e) {
                final var message = StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW;
                LoggingHelper.error().log(message, e.getMessage());
                new ScheduleException(message, e).printStackTrace();
            }
            final var initSize = Long.valueOf(1);
            LoggingHelper.info()
                    .workflowId(workflowService.getWorkflow().getName())
                    .log(methodName
                            + StringTemplates.WAITING_FOR_S_MS.formatted(this.stepDuration * initSize));
            executor.schedule(stepTask, this.stepDuration * initSize, TimeUnit.MILLISECONDS);
        };
    }

}
