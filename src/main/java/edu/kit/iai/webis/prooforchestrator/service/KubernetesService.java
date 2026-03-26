/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.service;

import edu.kit.iai.webis.prooforchestrator.config.OrchestrationConfig;
import edu.kit.iai.webis.prooforchestrator.container.BlockContainer;
import edu.kit.iai.webis.prooforchestrator.exception.ContainerException;
import edu.kit.iai.webis.prooforchestrator.util.StringTemplates;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.options.DeleteOptions;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Service class for container orchestration
 */
/*
@Service  do not create an instance automatically by spring boot to avoid necessity of a kubernetes cluster
*/
public class KubernetesService {

    private final OrchestrationConfig orchestrationConfig;
    private final GenericKubernetesApi<V1Pod, V1PodList> client;

    public KubernetesService(final OrchestrationConfig orchestrationConfig) {
        try {
            // Setup api client based on config
            this.orchestrationConfig = orchestrationConfig;

//            final var kubernetesApiClient = orchestrationConfig.getInCluster().orElseThrow() ?
//            		ClientBuilder.cluster().build() : ClientBuilder.standard().build();
            final var kubernetesApiClient = ClientBuilder.cluster().build();
            this.client = new GenericKubernetesApi<>(V1Pod.class, V1PodList.class, StringTemplates.API_GROUP,
                    StringTemplates.API_VERSION, StringTemplates.RESOURCE, kubernetesApiClient);
            Configuration.setDefaultApiClient(kubernetesApiClient);
        } catch (final IOException e) {
            final var message = StringTemplates.COULD_NOT_CREATE_K_8_S_API_CLIENT;
            LoggingHelper.error().log(message);
            throw new ContainerException(message, e);
        }
    }

    /**
     * Start a container for a specific block
     *
     * @param blockContainer Container of the block
     * @param executionId    Execution id of the runtime
     */
    public void startBlock(final BlockContainer blockContainer, final String executionId) {
        blockContainer.addEnvironmentVars(this.orchestrationConfig.getRabbitAndElasticConfig());
        if (LoggingHelper.isLevelDebugOrTrace()) {
            LoggingHelper.printHashMapContents(blockContainer.getEnvironmentVars(), System.out, "Environmental Variables read from application-dev.yaml");
        }

        try {
            final var containerName = String.format(StringTemplates.CONTAINER_NAME_TEMPLATE,
                    blockContainer.getWorkflowId(), blockContainer.getIndex());
            final var pod = new V1Pod()
                    .metadata(new V1ObjectMeta()
                            .name(containerName)
                            .labels(Map.of(StringTemplates.APP_LABEL, StringTemplates.PROOF_LABEL_VALUE))
                            .namespace(this.orchestrationConfig.getNameSpace().orElseThrow()))
                    .spec(
                            new V1PodSpec()
                                    .containers(List.of(
                                            new V1Container()
                                                    .env(blockContainer.getEnvironmentVars()
                                                            .entrySet()
                                                            .stream()
                                                            .map((final Entry<String, String> entry) -> {
                                                                final var envVar = new V1EnvVar();
                                                                envVar.setName(entry.getKey());
                                                                envVar.setValue(entry.getValue());
                                                                return envVar;
                                                            })
                                                            .collect(Collectors.toList()))
                                                    .name(containerName)
                                                    .imagePullPolicy(StringTemplates.ALWAYS_PULL_POLICY)
                                                    .image(blockContainer.getBlock().getContainerImage())
                                                    .resources(
                                                            new V1ResourceRequirements().limits(
                                                                    Map.of(
                                                                            StringTemplates.CPU_KEY,
                                                                            Quantity.fromString(StringTemplates.M1000),
                                                                            StringTemplates.MEMORY_KEY,
                                                                            Quantity.fromString(StringTemplates.M2000)
                                                                    )
                                                            )
                                                    )
                                    ))
                    );
            this.client.create(pod).throwsApiException().getObject();
            LoggingHelper.info()
                    .localBlockId(blockContainer.getIndex())
                    .globalBlockId(blockContainer.getGlobalId())
                    .workflowId(blockContainer.getWorkflowId())
                    .executionId(executionId)
                    .log(StringTemplates.POD_CREATED);
        } catch (final ApiException e) {
            final var message = StringTemplates.COULD_NOT_CREATE_POD;
            LoggingHelper.error()
                    .workflowId(blockContainer.getWorkflowId())
                    .executionId(executionId)
                    .log(message);
            throw new ContainerException(message, e);
        }
    }

    /**
     * Stop a container for a specific block
     *
     * @param blockContainer Container of the block
     * @param executionId    Execution id of the runtime
     */
    public void stopBlock(final BlockContainer blockContainer, final String executionId) {
        try {
            final var containerName = String.format(StringTemplates.CONTAINER_NAME_TEMPLATE,
                    blockContainer.getWorkflowId(), blockContainer.getIndex());
            final var deleteOptions = new DeleteOptions();
            this.client.delete(this.orchestrationConfig.getNameSpace().orElseThrow(), containerName, deleteOptions)
                    .throwsApiException().getObject();
            LoggingHelper.info()
                    .workflowId(blockContainer.getWorkflowId())
                    .executionId(executionId)
                    .log(StringTemplates.POD_DELETED);
        } catch (final ApiException e) {
            if (e.getCode() == 404) {
                LoggingHelper.info().executionId(executionId).workflowId(blockContainer.getWorkflowId())
                        .log("Already deleted");
            } else {
                LoggingHelper.error()
                        .workflowId(blockContainer.getWorkflowId())
                        .executionId(executionId)
                        .log(StringTemplates.COULD_NOT_DELETE_POD);
                throw new ContainerException(StringTemplates.COULD_NOT_DELETE_POD, e);
            }
        }
    }
}
