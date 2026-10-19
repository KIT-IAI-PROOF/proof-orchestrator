/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.util;

import java.util.Arrays;

public class StringTemplates {

    public static final String CONTAINER_NAME_TEMPLATE = "proof-block-%s-%s";
    public static final String API_GROUP = "";
    public static final String API_VERSION = "v1";
    public static final String RESOURCE = "pods";
    public static final String APP_LABEL = "iai.kit.edu/app";
    public static final String PROOF_LABEL_VALUE = "proof-2";
    public static final String CPU_KEY = "cpu";
    public static final String MEMORY_KEY = "memory";
    public static final String M1000 = "1000m";
    public static final String M2000 = "2000Mi";
    public static final String IS_ANOTHER_ACTIVE_EXECUTION_RUNNING_NOT_DELETING_IO = "There is another active execution"
            + " running, not deleting IO!";
    public static final String ALWAYS_PULL_POLICY = "Always";
    public static final String DIRTY_UNSTOPPED_EXECUTION = "Cleaning dirty unstopped execution of workflow with id %s";
    public static final String PROOF_WORKER_WORKFLOW_UUID_KEY = "PROOF_WORKER_WORKFLOW_UUID";
    public static final String PROOF_WORKER_WORKFLOW_EXECUTION_ID = "PROOF_WORKER_WORKFLOW_EXECUTIONID";
    public static final String PROOF_WORKER_BLOCK_UUID_KEY = "PROOF_WORKER_BLOCK_UUID";
    public static final String PROOF_WORKER_BLOCK_ID_KEY = "PROOF_WORKER_BLOCK_ID";
    public static final String PROOF_WORKER_BLOCK_CONFIG = "PROOF_WORKER_BLOCK_CONFIG";
    public static final String PROOF_WORKER_WORKFLOW_CONFIG = "PROOF_WORKER_WORKFLOW_CONFIG";
    public static final String RUNNING_BLOCK_DEPENDENCY_NOT_MET = "Running block dependency not met";
    public static final String POD_DELETED = "Pod deleted";
    public static final String COULD_NOT_DELETE_POD = "Could not delete Pod";
    public static final String COULD_NOT_CREATE_POD = "Could not create pod";
    public static final String POD_CREATED = "Pod created";
    public static final String NO_IOINTERFACES_IN_THIS_BLOCK = "There are no IOInterfaces in this block";
    public static final String KEY_S_IS_MISSING_IN_STATIC_INPUT_OBJECT = "Key (%s) is missing in static input object";
    public static final String PREPARED_WORKFLOW = "Workflow with id '%s' prepared";
    public static final String ALL_BLOCKS_REPORTED_AS_RUNNING = "All blocks reported as running";
    public static final String BLOCK_REPORTED_AS_ABORTED = "Block reported as aborted";
    public static final String WORKFLOW_WAS_ABORTED = "Workflow was aborted";
    public static final String WORKFLOW_WAS_SHUTDOWN = "Workflow was shut down";
    public static final String NO_WORKFLOW_CONFIG_AVAILABLE = "No workflow config for id %s available";
    public static final String NO_BLOCK_CONFIG_AVAILABLE = "No block config for id %s available";
    public static final String COULD_NOT_UPDATE_IOINTERFACE_STATUS = "Could not update IOInterface status in " +
            "execution %s";
    public static final String NO_WORKFLOWCONTAINER_KNOWN = "No WorkflowContainer with id %s known";
    public static final String STARTED_WORKFLOW = "Created and started workflow %s";
    public static final String AlREADY_RUNNING = "This workflow is already in execution with execution id %s";
    public static final String ALL_BLOCKS_COMPLETE_DOING_STEP = "All Blocks have completed, doing step";
    public static final String ALL_BLOCKS_FINALIZED = "All Blocks are finalized";
    public static final String ALL_BLOCKS_EXECUTION_FINISHED = "All Blocks have finished execution";
    public static final String ABORTED_STOPPING_SCHEDULING = "Workflow aborted, stopping scheduling";
    public static final String BLOCKS_INCOMPLETE_WAITING = "Not all Blocks have completed, waiting";
    public static final String EXECUTING_FINALIZE_TACT = "Executing finalize tact";
    public static final String EXECUTING_STEP_TACT = "Executing Step tact";
    public static final String EXECUTING_INIT_TACT = "Executing Init tact";
    public static final String FAILED_TO_SCHEDULE_WORKFLOW = "Failed to schedule workflow";
    public static final String WAITING_FOR_S_MS = "Waiting for %s ms";
    public static final String COULD_NOT_CREATE_K_8_S_API_CLIENT = "Could not create k8s api client";

    public static final String ALL_BLOCKS_ARE_CREATED = "All Blocks are CREATED";
    public static final String ALL_BLOCKS_ARE_INITIALIZED = "All Blocks are INITIALIZED";
    public static final String ALL_BLOCKS_ARE_EXECUTION_FINISHED = "All Blocks are EXECUTION_FINISHED";
    public static final String ALL_BLOCKS_ARE_FINALIZED = "All Blocks are FINALIZED";
    public static final String ALL_BLOCKS_ARE_SHUT_DOWN = "All Blocks are SHUT DOWN";
    public static final String ERROR_OCCURED_IN_BLOCK = "ERROR occured in Block %s";


    public static final String BLOCK_SHUTDOWN_RELEVANCE = "shutdownRelevance";

    private final static char STAR = '*';
    private final static char BLNK = ' ';

    public static String printStarBordered(String text) {
        return printStarBordered(text, 10, 3);
    }

    public static String printStarBordered(String text, int numStars, int numBlanks) {
        int len = text.length();
        int lineLen = len + 2 * (numStars + numBlanks);

        char[] lines = new char[3 * lineLen + 2];
        Arrays.fill(lines, 0, lines.length, STAR);
        lines[lineLen] = '\n';
        lines[2 * lineLen + 1] = '\n';
        char[] textc = text.toCharArray();
        for (int i = lineLen + numStars + 1, j = 0; j < numBlanks; i++, j++) {
            lines[i] = BLNK;
            lines[i + textc.length + numBlanks] = BLNK;
        }
        for (int i = lineLen + numStars + numBlanks + 1, j = 0; j < textc.length; i++, j++) {
            lines[i] = textc[j];
        }
        return "\n" + new String(lines);
    }

}
