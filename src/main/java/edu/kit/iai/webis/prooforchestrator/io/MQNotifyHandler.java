/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.io;

import edu.kit.iai.webis.prooforchestrator.service.OrchestrationService;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.message.NotifyMessage;

/**
 * Handler for incoming {@link NotifyMessage}s from the worker
 */
public class MQNotifyHandler {

    private final OrchestrationService orchestrationService;

    public MQNotifyHandler(OrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    /**
     * handle {@link NotifyMessage}s from the worker
     *
     * @param message the {@link NotifyMessage}
     */
    public void handleMessage(final NotifyMessage message) {

        LoggingHelper.debug().log("==== W ====> Notify Message (BlockStatus=%s) received from Block %s (%d)    (CP=%d)\n",
        		message.getBlockStatus(), message.getGlobalBlockId(), message.getLocalBlockId(),
                message.getCommunicationPoint());

        switch (message.getBlockStatus()) {
            case ERROR_INIT, ERROR_STEP, ERROR_FINALIZE -> {   // only received one time: after worker initialization (proof-worker.StartupRunner)
                LoggingHelper.error().messageColor(Colors.ANSI_RED)
                .log(LoggingHelper.printStarBordered("Error Message from block %d (%s): %s".formatted( message.getLocalBlockId(), message.getGlobalBlockId(), message.getErrorText())));
            }
            default -> {}
        }
        try {
            this.orchestrationService.setBlockStatus(message);
        } catch (Exception e) {
            LoggingHelper.error().messageColor(Colors.ANSI_RED)
            .log("Error while processing NotifyMessage from block %d (%s): %s\nMessage:".formatted( message.getLocalBlockId(), message.getGlobalBlockId(), e.getMessage(), message));
        }
    }
}
