/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.controller;

import static org.springframework.http.MediaType.ALL_VALUE;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import edu.kit.iai.webis.prooforchestrator.service.OrchestrationService;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.model.SimulationStatus;
import edu.kit.iai.webis.proofutils.wrapper.Execution;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Rest Interface to control workflow execution
 */
@RestController
public class OrchestrationController {

    private final OrchestrationService orchestrationService;

    public OrchestrationController(final OrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    /**
     * Run a workflow with a given id
     *
     * @param executionId UUID of the execution process for a workflow to get from config server
     * @return Returns the id of the prepared and started execution
     */
    @Operation(
            summary = "Start workflow",
            description = "Start workflow",
            security = @SecurityRequirement(name = "oauth2")
    )
    @ApiResponses(
            value = {
                    @ApiResponse(responseCode = "200", description = "Start successful", useReturnTypeSchema = true),
                    @ApiResponse(responseCode = "500", description = "Start incomplete", content = @Content(schema = @Schema(hidden = true))),
            })
    @PostMapping(value = "/v1/start", produces = MediaType.APPLICATION_JSON_VALUE, consumes = ALL_VALUE)
    public ResponseEntity<Execution> runWorkflow(@Parameter(description = "UUID of the workflow to start", required = true, schema = @Schema(implementation = String.class)) @RequestParam final String executionId) {
        try {
            final Execution result = this.orchestrationService.prepareWorkflow(null, executionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            LoggingHelper.debug().messageColor(Colors.ANSI_RED).log("Error preparing WF: Reason: %s", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Abort a specific workflow
     *
     * @return Status message as string
     */
    @Operation(
            summary = "Abort workflow",
            description = "Abort workflow",
            security = @SecurityRequirement(name = "oauth2")
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Abort successful", content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE)}),
            @ApiResponse(responseCode = "500", description = "Retrieving information failed", content = {@Content(mediaType = MediaType.TEXT_PLAIN_VALUE)})
    })
    @DeleteMapping(value = "/v1/abort", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> abortWorkflow(@Parameter(description = "UUID of the execution to abort", required = true, schema = @Schema(implementation = String.class)) @RequestParam final String executionId) {
        this.orchestrationService.abortWorkflow(executionId, SimulationStatus.STOPPED);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "v1/test", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> testEndpoint() {
        return ResponseEntity.ok("Test successful");
    }

}
