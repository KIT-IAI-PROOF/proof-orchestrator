/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import edu.kit.iai.webis.prooforchestrator.container.BlockContainer;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.message.SyncMessage;
import edu.kit.iai.webis.proofutils.message.ValueMessage;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.model.SimulationPhase;

/**
 * a helper class for the writing of messages (a  {@link ValueMessage} or a {@link SyncMessage}) to a file or to a
 * stream
 */
@Component
public class StatusHelper {

    private final int[] intStatusArray = new int[SimulationStatus.values().length];

    private List<BlockContainer> blockContainers = null;
    private BlockContainer[] blockContainerArray = null;
    private final boolean[] passedPhases = new boolean[SimulationPhase.values().length];
    private final List<BlockContainer> relevantBlockContainers = new ArrayList<BlockContainer>();


    public StatusHelper() {
    }

    /**
     * set the {@link BlockContainer}s
     *
     * @param blockContainers the {@link BlockContainer}s
     */
    public void setBlockContainers(List<BlockContainer> blockContainers) {
        this.blockContainers = blockContainers;
        /**
         *  for performance reasons: put the BlockContainers to an array.
         *  It cannot be guaranteed that the localBlockId will increase continuously and lie exactly between 0 and blockContainers.size()
         *  As there are only relatively few BlockContainers, it is not critical that there may be gaps in the array
         */
        int[] max = new int[1];
        this.blockContainers.forEach(b -> {
            if (b.getIndex() > max[0]) {
                max[0] = b.getIndex();
            }
        });

        this.blockContainerArray = new BlockContainer[max[0] + 1];

        this.blockContainers.forEach(b -> {
            this.blockContainerArray[b.getIndex()] = b;
            if (b.isShutdownRelevant()) {
                this.relevantBlockContainers.add(b);
            }
        });

        this.setStatusForAllBlocks(SimulationStatus.UNKNOWN);
        LoggingHelper.debug().log("\nSH: setBlockContainers: ################################## "
                + "#BCs: " + this.blockContainers.size() + "/" + (this.blockContainerArray.length - 1));
    }

    /**
     * set the status for all blocks (i.e. a {@link BlockContainer}s)
     *
     * @param blockContainer the block
     * @param status         the status
     */
    public synchronized void setStatusForAllBlocks(SimulationStatus status) {
        for (BlockContainer bC : this.blockContainerArray) {
            if (bC != null) {
                bC.setStatus(status);
            }
        }
        Arrays.fill(this.intStatusArray, 0);
        this.intStatusArray[status.ordinal()] = this.blockContainers.size();
    }

    /**
     * set the status of a block (i.e. a {@link BlockContainer})
     *
     * @param blockContainer the block
     * @param status         the status
     */
    public synchronized void setBlockStatus(BlockContainer blockContainer, SimulationStatus status) {
        this.setBlockStatus(blockContainer.getIndex(), status);
    }

    /**
     * set the status of a block (i.e. a {@link BlockContainer}) given by the local block id
     *
     * @param localBlockId the block id
     * @param status       the status
     */
    public synchronized void setBlockStatus(Integer localBlockId, SimulationStatus newStatus) {
        if (this.blockContainerArray[localBlockId] != null) {
            LoggingHelper.debug().log("\nSH: SetBlockStatus: ################################## Block " + localBlockId + ", Status:  OLD: " + this.blockContainerArray[localBlockId].getStatus() + ",  NEW: " + newStatus + " ################################## ");
            // decrease old status:
            this.decreaseOldStatusCounter(this.blockContainerArray[localBlockId].getStatus());
            this.blockContainerArray[localBlockId].setStatus(newStatus);
            // increase new status:
            this.increaseNewStatusCounter(newStatus);
        }
    }

    private synchronized void increaseNewStatusCounter(SimulationStatus status) {
        this.intStatusArray[status.ordinal()]++;
        if (this.intStatusArray[status.ordinal()] == this.blockContainers.size()) {
            this.logPassedPhases(status);
        }
        if (status != SimulationStatus.READY) {
            this.logSummary();
        }
    }

    private void logPassedPhases(SimulationStatus status) {

        switch (status) {
            case CREATED -> {
                this.passedPhases[SimulationPhase.CREATE.ordinal()] = true;
                LoggingHelper.info().log(LoggingHelper.printStarBordered(StringTemplates.ALL_BLOCKS_ARE_CREATED));
            }
            case INITIALIZED -> {
                this.passedPhases[SimulationPhase.INIT.ordinal()] = true;
                LoggingHelper.info().log(LoggingHelper.printStarBordered(StringTemplates.ALL_BLOCKS_ARE_INITIALIZED));
            }
            case EXECUTION_FINISHED -> {
                this.passedPhases[SimulationPhase.EXECUTE.ordinal()] = true;
                LoggingHelper.info().log(LoggingHelper.printStarBordered(StringTemplates.ALL_BLOCKS_ARE_EXECUTION_FINISHED));
            }
            case FINALIZED -> {
                this.passedPhases[SimulationPhase.FINALIZE.ordinal()] = true;
                LoggingHelper.info().log(LoggingHelper.printStarBordered(StringTemplates.ALL_BLOCKS_ARE_FINALIZED));
            }
            case SHUT_DOWN -> {
                this.passedPhases[SimulationPhase.SHUTDOWN.ordinal()] = true;
                LoggingHelper.info().log(LoggingHelper.printStarBordered(StringTemplates.ALL_BLOCKS_ARE_SHUT_DOWN));
            }
            case ABORTED, EXECUTION_STEP_FINISHED, READY, WAITING -> {
//			return; // no logging output
            }
            case ERROR_INIT, ERROR_STEP, ERROR_FINALIZE -> {
//			LoggingHelper.error()
//			.log(StringTemplates.printStarBordered(StringTemplates.ERROR_OCCURED_IN_BLOCK.formatted(b.getLocalBlockId())));
            }
            default -> LoggingHelper.warn().log("Unexpected value: " + status);
        }

        if (LoggingHelper.isLevelDebugOrTrace()) {
            System.out.println("SH: SetBlockStatus: ################################## passed Phases: C / I / E / F / S: "
                    + this.passedPhases[SimulationPhase.CREATE.ordinal()] + ", "
                    + this.passedPhases[SimulationPhase.INIT.ordinal()] + ", "
                    + this.passedPhases[SimulationPhase.EXECUTE.ordinal()] + ", "
                    + this.passedPhases[SimulationPhase.FINALIZE.ordinal()] + ", "
                    + this.passedPhases[SimulationPhase.SHUTDOWN.ordinal()] + "  ############################################\n ");
        }

    }

    private void logSummary() {
        if (LoggingHelper.isLevelDebugOrTrace()) {
            System.out.println("SH: SetBlockStatus: ################################## #BCs with Statuses: "
                    + "R=" + this.intStatusArray[SimulationStatus.READY.ordinal()]
                    + ", W=" + this.intStatusArray[SimulationStatus.WAITING.ordinal()]
                    + ", C=" + this.intStatusArray[SimulationStatus.CREATED.ordinal()]
                    + ", I=" + this.intStatusArray[SimulationStatus.INITIALIZED.ordinal()]
                    + ", ESF=" + this.intStatusArray[SimulationStatus.EXECUTION_STEP_FINISHED.ordinal()]
                    + ", EF=" + this.intStatusArray[SimulationStatus.EXECUTION_FINISHED.ordinal()]
                    + ", F=" + this.intStatusArray[SimulationStatus.FINALIZED.ordinal()]
                    + ", S=" + this.intStatusArray[SimulationStatus.SHUT_DOWN.ordinal()]
                    + "  [ EI=" + this.intStatusArray[SimulationStatus.ERROR_INIT.ordinal()]
                    + " ES=" + this.intStatusArray[SimulationStatus.ERROR_STEP.ordinal()]
                    + " EF=" + this.intStatusArray[SimulationStatus.ERROR_FINALIZE.ordinal()] + " ]  of " + this.blockContainers.size()
                    + " ################################## ");
        }
    }

    private synchronized void decreaseOldStatusCounter(SimulationStatus status) {
        if (this.intStatusArray[status.ordinal()] > 0) {
            this.intStatusArray[status.ordinal()]--;
        }
    }

    /**
     * check whether any {@link BlockContainer} has the given {@link SimulationStatus}
     * ({@link SimulationStatus#ABORTED}, {@link SimulationStatus#FINALIZED}, or {@link SimulationStatus#SHUT_DOWN})
     *
     * @return true, if any {@link BlockContainer} ar aborted, false, if all of them are running
     */
    public synchronized boolean isAnyStatus(SimulationStatus blockStatus) {
        return this.intStatusArray[blockStatus.ordinal()] > 0;
    }

    /**
     * check whether one or more {@link BlockContainer}s has error status ({@link SimulationStatus#ERROR_INIT},
     * {@link SimulationStatus#ERROR_STEP} or {@link SimulationStatus#ERROR_FINALIZE})
     *
     * @return true, if any {@link BlockContainer} has error status, false, if all of them contain no error status
     */
    public synchronized boolean existsErrorSimulationStatus() {

        return (this.intStatusArray[SimulationStatus.ERROR_INIT.ordinal()]
                + this.intStatusArray[SimulationStatus.ERROR_STEP.ordinal()]
                + this.intStatusArray[SimulationStatus.ERROR_FINALIZE.ordinal()]) > 0;
    }

    /**
     * check whether all {@link BlockContainer}s have the same {@link SimulationStatus}
     *
     * @return true, if all {@link BlockContainer}s have the same {@link SimulationStatus}, false, if not
     */
    public synchronized boolean areAllStatus(SimulationStatus status) {
        boolean res = this.intStatusArray[status.ordinal()] == this.blockContainers.size();
        if (LoggingHelper.isLevelDebugOrTrace()) {
            System.out.println("SH: haveAllBlockContainersSameStatus: " + status + "? " + " ==> " + (res ? "YES" : "NO"));
            System.out.print("Blocks: ");
            this.blockContainers.forEach(bc -> {
                System.out.print("\t(" + bc.getIndex() + "): " + bc.getStatus());
            });
            System.out.println();
        }
        return res;
    }

    /**
     * check whether a (shutdown-) relevant block has finalized
     *
     * @return
     */
    public synchronized boolean hasAShutdownRelevantBlockTheStatus(SimulationStatus blockStatus) {
        for (BlockContainer bc : this.relevantBlockContainers) {
            if (bc.getStatus().equals(blockStatus)) {
                LoggingHelper.info().log("The shutdown-relevant Block %d (%s) has the SimulationStatus %s  -> finalizing and stopping all other bocks ...", bc.getIndex(), bc.getGlobalId(), blockStatus.toString());
                return true;
            }
        }
        return false;
    }

    /**
     * check whether a block has a given status
     *
     * @return
     */
    public synchronized boolean hasTheBlockContainerTheStatus(BlockContainer blockContainer, SimulationStatus blockStatus) {
        return this.blockContainerArray[blockContainer.getIndex()].getStatus() == blockStatus;
    }

    /**
     * check whether any {@link BlockContainer} is terminated
     * ({@link SimulationStatus#ABORTED}, {@link SimulationStatus#FINALIZED}, or {@link SimulationStatus#SHUT_DOWN})
     *
     * @return true, if any {@link BlockContainer} is terminated, false, if all of them are running
     */
    public synchronized boolean isAnyBlockContainerTerminated() {
        final boolean terminated =
                this.intStatusArray[SimulationStatus.ABORTED.ordinal()]
                        + this.intStatusArray[SimulationStatus.FINALIZED.ordinal()]
                        + this.intStatusArray[SimulationStatus.SHUT_DOWN.ordinal()] > 0;

        LoggingHelper.trace().log("?    %s       ==> ABORTED=%d, FINALIZED=%d, SHUT_DOWN=%d", (terminated ? "YES" : "NO"),
                this.intStatusArray[SimulationStatus.ABORTED.ordinal()],
                this.intStatusArray[SimulationStatus.FINALIZED.ordinal()],
                this.intStatusArray[SimulationStatus.SHUT_DOWN.ordinal()]);

        return terminated;
    }

    /**
     * check whether the simulation has passed a given phase ({@link SimulationPhase})
     *
     * @param phase the phase
     * @return true, if the given phase is passed, false, otherwise
     */
    public boolean hasSimulationPassedPhase(SimulationPhase phase) {
        LoggingHelper.trace().log("has phase '%s' passed? -> %s", phase, this.passedPhases[phase.ordinal()]);
        return this.passedPhases[phase.ordinal()];
    }


}
