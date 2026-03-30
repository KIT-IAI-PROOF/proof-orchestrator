/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import edu.kit.iai.webis.prooforchestrator.exception.ScheduleException;
import edu.kit.iai.webis.prooforchestrator.util.StatusHelper;
import edu.kit.iai.webis.prooforchestrator.util.StringTemplates;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.io.MQSyncProducer;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.model.SimulationPhase;


@Service
public class ScheduleService {

    private final MQSyncProducer mqSyncProducer;
    private final StatusHelper statusHelper;
    private ScheduledExecutorService executor = null;
    private Long stepDuration = 200L;

    public ScheduleService(final MQSyncProducer mqSyncProducer,
                           final StatusHelper statusHelper) {
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
        if (this.executor != null) {
            this.executor.shutdownNow();
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
                String msg = "";

                try {
                    if (ScheduleService.this.statusHelper.existsErrorSimulationStatus()) {
                        workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.SHUTDOWN);
                        LoggingHelper.error().workflowId(workflowService.getWorkflow().getId())
                                .log("Error block status is found! NO more SyncMessage will be sent! => sending SHUTDOWN to all blocks");
                    }
                    if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.SHUT_DOWN)) {
                    	LoggingHelper.info().log("---> all blocks are shut down => stopping execution");
                    	executor.shutdownNow();
                    	return;
                    }
                    else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.SHUT_DOWN))
                    {
                    	LoggingHelper.info().log("a block has shut down => sending SHUTDOWN SYNC to all Blocks ...");
                    	workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.SHUTDOWN);
                    }
                    else if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.FINALIZED)) {
                    	LoggingHelper.info().log("All block containers have finalized, sending  SHUTDOWN SYNC message!");
                    	workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.SHUTDOWN);
                    }
                    else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.FINALIZED)) {
                    	// REFACTOR: check for shutdown relevant blocks
                        LoggingHelper.info().log("One or more block containers have finalized, sending SHUTDOWN SYNC message!");
                        workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.SHUTDOWN);
                    }
                    else if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.EXECUTION_FINISHED)) {
                    	LoggingHelper.info().log(StringTemplates.ALL_BLOCKS_ARE_EXECUTION_FINISHED);
                    	workflowService.sendSyncMessage(
                    			ScheduleService.this.mqSyncProducer,
                    			SimulationPhase.FINALIZE);
                    }
                    else if (ScheduleService.this.statusHelper.hasAShutdownRelevantBlockTheStatus(SimulationStatus.EXECUTION_FINISHED)) {
                        workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.FINALIZE);
                    }
                    else if (ScheduleService.this.statusHelper.isAnyStatus(SimulationStatus.EXECUTION_FINISHED)) {
                        // REFACTOR: check for shutdown relevant blocks, but: see above hasAShutdownRelevantBlockTheStatus
                        LoggingHelper.info().log(LoggingHelper.printStarBordered("A Block that is not relevant for a workflow shutdown has finished its execution"));
                        workflowService.prepareNextStepForAllBlocks();
                        workflowService.sendSyncMessage(ScheduleService.this.mqSyncProducer, SimulationPhase.EXECUTE);
                    }
                    else {
                        // All blocks are initialized
                        switch (workflowService.getWorkflow().getSimulationStrategy()) {
                            case WAIT_AND_CONTINUE, WAIT_AND_RETRY -> {

                                boolean allFinished = ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.EXECUTION_STEP_FINISHED);
                                boolean allReady = ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY);
                                boolean allInitialized = ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.INITIALIZED);
                                boolean readyForSync = allFinished || allReady || allInitialized;
                                LoggingHelper.debug().log("Strategy: WAC, WAR: allFinished: %b, allReady: %b, allInitialized: %b => readyForSync: %b", allFinished, allReady, allInitialized, readyForSync);
                                if (readyForSync) {
                                    workflowService.prepareNextStepForAllBlocks();
                                    // send new sync message
                                    LoggingHelper.info().log(StringTemplates.ALL_BLOCKS_COMPLETE_DOING_STEP);
                                    workflowService.sendSyncMessage(
                                            ScheduleService.this.mqSyncProducer,
                                            SimulationPhase.EXECUTE);
                                } else {
                                    // Not all blocks finished - wait for more blocks
                                    LoggingHelper.debug().log("Waiting for all blocks to finish step");
                                }
                            }
                            case IGNORE -> {
                                    workflowService.sendSyncMessage(
                                            ScheduleService.this.mqSyncProducer,
                                            SimulationPhase.EXECUTE);
//                                }
                            }
                            default -> throw new IllegalArgumentException("Unexpected value: " + workflowService.getWorkflow().getSimulationStrategy());
                        }
                    }
                } catch (final Exception e) {
                    LoggingHelper.error().log(StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW);
                    new ScheduleException(StringTemplates.FAILED_TO_SCHEDULE_WORKFLOW, e).printStackTrace();
                }
                final Long stepSize = Long.valueOf(1);

//                LoggingHelper.debug().workflowId(workflowService.getWorkflow().getId())
//                        .log(methodName + "\t" + msg + StringTemplates.WAITING_FOR_S_MS.formatted(ScheduleService.this.stepDuration * stepSize));
                executor.schedule(this, ScheduleService.this.stepDuration * stepSize, TimeUnit.MILLISECONDS);
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
                        case WAIT_AND_CONTINUE -> {

                            if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY)
                                    || ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.INITIALIZED)) {
                                LoggingHelper.info().log(StringTemplates.ALL_BLOCKS_COMPLETE_DOING_STEP);
                                workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.EXECUTE);
                            } else {// Skip step and wait until all blocks finish their tasks and send back notify
                                // messages
                                LoggingHelper.info().log(methodName
                                        + StringTemplates.BLOCKS_INCOMPLETE_WAITING);
                                LoggingHelper.info().log(methodName
                                        + ": Skip step and wait until all blocks finish "
                                        + "their tasks and send back notify messages");
                            }
                        }
                        case WAIT_AND_RETRY -> {

                            if (ScheduleService.this.statusHelper.areAllStatus(SimulationStatus.READY)) {
                                LoggingHelper.info().log(methodName
                                        + StringTemplates.ALL_BLOCKS_COMPLETE_DOING_STEP);
                                workflowService.sendSyncMessage(this.mqSyncProducer, SimulationPhase.EXECUTE);
                            }
                            LoggingHelper.info().log(methodName
                                    + StringTemplates.BLOCKS_INCOMPLETE_WAITING);
                            LoggingHelper.info().log(methodName
                                    + ": Skip step and wait a duration then send the same sync messages again");
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
