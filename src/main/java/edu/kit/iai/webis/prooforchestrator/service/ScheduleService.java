/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import edu.kit.iai.webis.prooforchestrator.config.OrchestrationConfig;
import edu.kit.iai.webis.prooforchestrator.exception.ScheduleException;
import edu.kit.iai.webis.prooforchestrator.util.StatusHelper;
import edu.kit.iai.webis.prooforchestrator.util.StringTemplates;
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

    /**
     * Scheduled timeout task for the notify watchdog.
     * It is canceled as soon as all worker Notify messages have been received.
     */
    private ScheduledFuture<?> notifyTimeoutTask = null;
    /**
     * Timestamp of the first execution step that is waiting for Notify messages.
     * Used to report the elapsed waiting time when the last Notify arrives or when a timeout happens.
     */
    private Long notifyTimeoutStartedAt = null;

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

        LoggingHelper.info().log("Scheduling workflow %s with step duration: %d", workflowService.getWorkflow().getName(), this.stepDuration);
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
        // Stop the notify watchdog first so no delayed shutdown action can fire after the scheduler stops.
        this.cancelNotifyTimeout();
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    /**
     * Cancels the notify watchdog without logging a completion message.
     * This is used when scheduling moves on for unrelated reasons, such as shutdown or finalize.
     */
    public synchronized void cancelNotifyTimeout() {
        if (this.notifyTimeoutTask != null) {
            this.notifyTimeoutTask.cancel(false);
            this.notifyTimeoutTask = null;
        }
        this.notifyTimeoutStartedAt = null;
    }

    /**
     * Completes the notify watchdog after the last worker has replied.
     * The elapsed waiting time is logged before the watchdog state is cleared.
     */
    public synchronized void completeNotifyTimeout() {
        if (this.notifyTimeoutStartedAt != null) {
            final long elapsedMillis = Math.max(0L, System.currentTimeMillis() - this.notifyTimeoutStartedAt);
            LoggingHelper.info().log("Orchestrator received all Notify messages after %d ms", elapsedMillis);
        }
        this.cancelNotifyTimeout();
    }

    /**
     * Starts a timeout watchdog after sync messages were sent (for the WAIT_AND_CONTINUE strategy).
     * If not all workers have reported back before the configured timeout elapses, the workflow is shut down.
     */
    private synchronized void startNotifyTimeout(final WorkflowService workflowService) {
        this.cancelNotifyTimeout();

        if (!workflowService.isWorkflowSimulationStrategy(SimulationStrategy.WAIT_AND_CONTINUE)) {
            return;
        }

        final Long workerTimeout = workflowService.getWorkflow().getStepBasedConfig().getWorkerTimeout();
        if (workerTimeout == null || workerTimeout <= 0) {
            LoggingHelper.debug().log("notify watchdog disabled because proof.orchestration.worker.timeout is not set");
            return;
        }

        final long workerTimeoutMillis = TimeUnit.SECONDS.toMillis(workerTimeout);
        LoggingHelper.info().log("notify watchdog started with timeout %d ms", workerTimeoutMillis);
        this.notifyTimeoutStartedAt = System.currentTimeMillis();

        this.notifyTimeoutTask = this.executor.schedule(() -> {
            final Long startedAt = ScheduleService.this.notifyTimeoutStartedAt;
            synchronized (ScheduleService.this) {
                if (ScheduleService.this.notifyTimeoutTask == null) {
                    return;
                }

                // If no worker is still waiting, a Notify message already completed the cycle.
                if (!ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.WAITING)) {
                    ScheduleService.this.notifyTimeoutTask = null;
                    return;
                }

                ScheduleService.this.notifyTimeoutTask = null;
            }

            // The timeout expired while at least one worker was still waiting.
            final long elapsedMillis = startedAt == null ? workerTimeoutMillis : Math.max(0L, System.currentTimeMillis() - startedAt);
            LoggingHelper.error().log(LoggingHelper.printStarBordered("Orchestrator error: Notify timeout reached after %d ms -> sending SHUTDOWN to all workers".formatted(elapsedMillis)));
            workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.SHUTDOWN);
        }, workerTimeoutMillis, TimeUnit.MILLISECONDS);
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
                String msg = "";

                if( LoggingHelper.isLevelDebugOrTrace() ) {
                	ScheduleService.this.statusHelper.printBlockStatuses();
                }
                try {
                    boolean triggerShutdownBlocks = false;
                    if (ScheduleService.this.statusHelper.existsErrorSimulationStatus()) {
                        LoggingHelper.error().workflowId(workflowService.getWorkflow().getId())
                                .log("Error block status is found! NO more SyncMessage will be sent! => sending SHUTDOWN to all blocks");
                        triggerShutdownBlocks = true;
                        ScheduleService.this.cancelNotifyTimeout();
                    }
                    if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.SHUT_DOWN)) {
                    	LoggingHelper.info().log("---> all blocks are shut down => stopping execution");
                    	executor.shutdownNow();
                        ScheduleService.this.cancelNotifyTimeout();
                    	return;
                    }
                    else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.SHUT_DOWN))
                    {
                    	LoggingHelper.info().log("a block has shut down => sending SHUTDOWN SYNC to all Blocks ...");
                        triggerShutdownBlocks = true;
                        ScheduleService.this.cancelNotifyTimeout();
                    }
//TODO: schauen, ob Queues auch bei IGNORE abgebaut werden
                    // RL TODO Test
                    else if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.FINALIZED)) {
                    	LoggingHelper.info().log("All block containers have finalized, sending  SHUTDOWN SYNC message!");
                        triggerShutdownBlocks = true;
                        ScheduleService.this.cancelNotifyTimeout();
                    }
                    else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.FINALIZED)) {
                        LoggingHelper.info().log("One or more block containers have finalized, sending SHUTDOWN SYNC message!");
                        triggerShutdownBlocks = true;
                        ScheduleService.this.cancelNotifyTimeout();
                    }
                    else if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.EXECUTION_FINISHED)) {
                    	LoggingHelper.info().log(StringTemplates.ALL_BLOCKS_ARE_EXECUTION_FINISHED);
                        ScheduleService.this.cancelNotifyTimeout();
                    	workflowService.sendSyncMessage(
                    			ScheduleService.this.mqSyncProducer,
                    			SimulationPhase.FINALIZE);
                    }
                    else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.EXECUTION_FINISHED)) {
                    	if (ScheduleService.this.statusHelper.hasAShutdownRelevantBlockTheStatus(SimulationStatus.EXECUTION_FINISHED)) {
                    		LoggingHelper.info().log("A Block that is relevant for a workflow shutdown has finished its execution. Finalizing ...");
                            ScheduleService.this.cancelNotifyTimeout();
                            workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.FINALIZE);
                    	}
                    	else {
                    		LoggingHelper.info().log("One or more blocks that are not relevant for a workflow shutdown has finished their execution");
                    		LoggingHelper.info().log(ScheduleService.this.statusHelper.getListOfBlocksWithStatus(SimulationStatus.EXECUTION_FINISHED)
                    									+ ", continuing the simulation ... ");
                    		workflowService.prepareNextStepForAllBlocks();
                    		workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.EXECUTE);
                    	}
                    }

                    else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.WAITING)) {
                    	LoggingHelper.debug().log("A Block has still the status WAITING, doing nothing ...");
                    }
                    else {
                        // All blocks have the status EXECUTION_STEP_FINISHED, READY or INITIALIZED
                        switch (workflowService.getWorkflow().getSimulationStrategy()) {
	                        case WAIT_AND_CONTINUE -> {

	                        	if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.EXECUTION_STEP_FINISHED)
	                        			|| ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY)
	                        			|| ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.INITIALIZED)
	                        	) {
	                        		// set the status to READY
	                        		workflowService.prepareNextStepForAllBlocks();
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
                                	System.out.println("\nSS::  no status is RETRY:  ============>>>>> ELSE <<<<<========== \n");
                                }
                            }
                            case IGNORE -> {
                            }
                            default -> {
                            	throw new IllegalArgumentException("Unexpected value: " + workflowService.getWorkflow().getSimulationStrategy());
                            }
                        } // switch()
                        workflowService.sendSyncMessage(
                        		ScheduleService.this.mqSyncProducer,
                        		SimulationPhase.EXECUTE);
                        if (workflowService.isWorkflowSimulationStrategy(SimulationStrategy.WAIT_AND_CONTINUE)) {
                            ScheduleService.this.startNotifyTimeout(workflowService);
                        }
                    } //else

                    if (triggerShutdownBlocks && !shuttingDown ) {
                        workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.SHUTDOWN);
                        shuttingDown = true;
                    }
                } catch (final Exception e) {
                    LoggingHelper.error().log(StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW);
                    new ScheduleException(StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW, e).printStackTrace();
                }
                final Long stepSize = Long.valueOf(1);

//                LoggingHelper.debug().workflowId(workflowService.getWorkflow().getId())
//                        .log(methodName + "\t" + msg + StringTemplates.WAITING_FOR_S_MS.formatted(ScheduleService.this.stepDuration * stepSize));
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
                    LoggingHelper.info().log("\n===================="
                            + StringTemplates.ABORTED_STOPPING_SCHEDULING
                            + "\n========================");
                    executor.shutdownNow();
                } else if (this.statusHelper.existsErrorSimulationStatus()) {
                    LoggingHelper.error().log("Error block status is found! NO more SyncMessage!");
                } else {
                    switch (workflowService.getWorkflow().getSimulationStrategy()) {
                        case WAIT_AND_CONTINUE, WAIT_AND_RETRY -> {

                            if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY)
                                    || ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.INITIALIZED)) {
                                LoggingHelper.info().log(StringTemplates.ALL_BLOCKS_COMPLETE_DOING_STEP);
                                workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.EXECUTE);
                                if (workflowService.isWorkflowSimulationStrategy(SimulationStrategy.WAIT_AND_CONTINUE)) {
                                    ScheduleService.this.startNotifyTimeout(workflowService);
                                }
                            } else {// Skip step and wait until all blocks finish their tasks and send back notify
                                // messages
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
                LoggingHelper.error().log(message);
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
                LoggingHelper.error().log(message);
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
