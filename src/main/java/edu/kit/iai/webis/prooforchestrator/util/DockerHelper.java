/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import edu.kit.iai.webis.prooforchestrator.config.OrchestrationConfig;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Workflow;

import edu.kit.iai.webis.proofutils.CommonStringTemplates;

@Component
public class DockerHelper {

    private final OrchestrationConfig orchestrationConfig;

    @Value("${proof.volume}")
    private String filesVolumeName;

    /**
     * the used spring processing profile
     */
    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /**
     * the base path to the ConigManager. Default is 'http://proof-config-manager:8100'
     */
    @Value("${proof.config.worker.basePath:http://proof-config-manager:8100}")
    private String configBasePath;

    /**
     * the logging level for the workers.
     * This environment variable is passed on to the start of the worker docker containers
     */
	@Value("${proof.worker.logLevel:INFO}")
	private String workerLoggingLevel;

	/**
	 * the logging directory for the workers.
	 * This environment variable is passed on to the start of the worker docker containers
	 */
	@Value("${proof.worker.logging.directory:/tmp/proof/logs}")
	private String workerLoggingDir;

    /**
     * the rabbitmq host of the worker.
     * Default is the same as the orchestrator's host, but can be set differently
     */
    @Value("${spring.rabbitmq.worker.host:${spring.rabbitmq.host}}")
    private String workerRabbitHost;


	public DockerHelper(final OrchestrationConfig orchestrationConfig) {
		this.orchestrationConfig = orchestrationConfig;
	}

    /**
     * Get the logging directory for a worker based on the execution id. 
     * If a placeholder is used in the configuration, it is replaced and stored in the workerLoggingDir variable 
     * in the first call of this method. 
     * @param executionId Id of the execution.
     * @return the workerLoggingDir without placeholder
     */
    private String getWorkerLogDir(String executionId) {
        return this.workerLoggingDir.replace(CommonStringTemplates.EXECUTION_ID_PLACEHOLDER, executionId);
    }

	/**
	 * get the environment variables for the start of a worker docker container
	 * @param workflow the id of the workflow
	 * @param block the (worker) block to be started in a container
	 * @param executionId the execution id to refer to
	 * @return the environment variables as a String list
	 */
    public List<String> getWorkerEnvVars(Workflow workflow, Block block, String executionId, String workerLogDir) {
        List<String> envVars = new ArrayList<>();
        envVars.add("proof.worker.workflow.uuid=" + workflow.getId());
        envVars.add("proof.worker.block.uuid=" + block.getId());
        envVars.add("proof.worker.block.id=" + block.getIndex());
        envVars.add("proof.worker.workflow.executionId=" + executionId);
        envVars.add("proof.config.basePath=" + this.configBasePath);
        envVars.add("spring.rabbitmq.host=" + this.workerRabbitHost);
        envVars.add("spring.profiles.active="+this.activeProfile);
        envVars.add("proof.workspace.directory="+this.orchestrationConfig.getWorkspaceDir());
        envVars.add("proof.attachments.directory="+this.orchestrationConfig.getAttachmentsDir());
        envVars.add("proof.userdata.directory="+this.orchestrationConfig.getUserdataDir());
        envVars.add("proof.worker.logging.logLevel=" + this.workerLoggingLevel);
        envVars.add("proof.worker.logging.directory=" + workerLogDir);
        envVars.add("app.name=" + block.getId());
        return envVars;
    }

    /**
     * check the existence of an docker image to be started as a container. For all needed images that do not exist,
     * an error message is logged and <b>false</b> is returned.
     * @param workflow the workflow containing the blocks
     * @param imageList the list of all available docker images on the host system
     * @return true, if all needed images exist, false otherwise
     */
    private boolean checkImageExistence( Workflow workflow, List<Image> imageList ) {
    	final HashSet<String> imageNames = new HashSet<>();

    	imageList.forEach(img -> {
    		final String[] repoTags = img.getRepoTags();
    		if( repoTags != null && repoTags.length > 0) {
    			imageNames.addAll(List.of(repoTags));
    		}
    	});

    	List<Block> nonExImages = workflow.getBlocks().values().stream().filter(b -> (!imageNames.contains( b.getContainerImage()) )).toList();
    	if( nonExImages.size() > 0 ) {
    		nonExImages.forEach(b -> {
    			LoggingHelper.error().messageColor(Colors.ANSI_RED_BOLD).log("Container image %s not found!", b.getContainerImage());
    		});
    		LoggingHelper.info().messageColor(Colors.ANSI_RED_BOLD).log("Container image(s) not found, workflow not started, check your template configuration!");
    		return false;
    	}
    	return true;
    }

    /**
     * process a workflow execution based on docker containers
     * @param workflow the workflow and its blocks to be started as docker containers
     * @param executionID the workflow execution id
     */
    public void processDockerExecution(Workflow workflow, String executionID) {
        try (DockerClient docker = DockerClientBuilder.getInstance()
                .withDockerHttpClient(new ApacheDockerHttpClient.Builder()
                        .dockerHost(DefaultDockerClientConfig.createDefaultConfigBuilder()
                                .build()
                                .getDockerHost())
                        .build())
                .build()) {

            ExecutorService executor = Executors.newFixedThreadPool(workflow.getBlocks().size());

            // Create the log directory for the workers to prevent errors when multiple workers try to create the same directory at the same time
            String workerLogDir = getWorkerLogDir(executionID);
            Files.createDirectories(Path.of(workerLogDir));
            String workspaceDir = this.orchestrationConfig.getWorkspaceDir();
            
            for (Block block : workflow.getBlocks().values()) {
                executor.submit(() -> {
                    String imageLocation = block.getContainerImage();
                    String name = block.getName().replaceAll("\\s", "") + "-" + block.getIndex();
                    LoggingHelper.info().log("Starting block '" + block.getName() + "' as '" + name + "' with image: " + imageLocation);

                    try {
                        docker.removeContainerCmd(name)
                                .withForce(true)
                                .exec();
                    } catch (Exception ignored) {

                    }
                    LoggingHelper.info().log("Block uuid: " + block.getId());
                    LoggingHelper.info().log("Block id: " + block.getIndex());
                    Bind pyFiles = new Bind("proof-files", new Volume(workspaceDir));
                    try {
                    	HostConfig hostConfig = HostConfig.newHostConfig()
                    			.withNetworkMode("proof")
                    			.withBinds(pyFiles)
                    			.withExtraHosts("host.docker.internal:host-gateway");
                        List<String> envVars = this.getWorkerEnvVars(workflow, block, executionID, workerLogDir);
                        // Log the equivalent Docker CLI command for debugging
                        StringBuilder dockerCmd = new StringBuilder("docker run -d");
                        dockerCmd.append(" --name ").append(name);
                        dockerCmd.append(" --network proof");
                        dockerCmd.append(" -v proof-files:" + workspaceDir);
                        for (String envVar : envVars) {
                            dockerCmd.append(" -e \"").append(envVar).append("\"");
                        }
                        dockerCmd.append(" ").append(imageLocation);

                        CreateContainerResponse container;
                        try {

                            container = docker.createContainerCmd(imageLocation)
                                    .withHostConfig(hostConfig)
                                    .withName(name)
                                    .withEnv(envVars)
                                    .exec();
                        } catch (NotFoundException e) {
                            LoggingHelper.info().log("Image %s not found locally, pulling...", imageLocation);
                            try {
                                docker.pullImageCmd(imageLocation)
                                        .exec(new PullImageResultCallback() {
                                            @Override
                                            public void onError(Throwable throwable) {
                                                LoggingHelper.error().log(
                                                        "Failed to pull image %s: %s",
                                                        imageLocation,
                                                        throwable.getMessage()
                                                );
                                                super.onError(throwable);
                                            }
                                        }).awaitCompletion();
                            } catch (InterruptedException ie) {
                                LoggingHelper.warn().log("Pulling image %s was interrupted: %s", imageLocation, ie.getMessage());
                            } catch (DockerException de) {
                                LoggingHelper.error().log("Failed to pull image %s: %s", imageLocation, e.getMessage());
                            }

                            // Retry creating the container
                            try {
                                container = docker.createContainerCmd(imageLocation)
                                        .withHostConfig(hostConfig)
                                        .withName(name)
                                        .withEnv(envVars)
                                        .exec();
                            } catch (NotFoundException de) {
                                LoggingHelper.error().log("Container image %s could not be found after trying to pull. Reason: %s", imageLocation, de.getMessage());
                                LoggingHelper.error().log("Container could not be started.");
                                LoggingHelper.error().log("Equivalent Docker command (retry): %s", dockerCmd.toString());
                                return;
                            }
                        } catch (Exception e) {
                            LoggingHelper.error().log("Failed to create container %s: %s", name, e.getMessage());
                            LoggingHelper.error().log("Equivalent Docker command: %s", dockerCmd.toString());
                            return;
                        }
                    	docker.startContainerCmd(container.getId()).exec();
                    	LoggingHelper.info().log("Container '%s' started", name);
					} catch (NotFoundException e) {
	                    LoggingHelper.error().log("Container image %s could not be started. Reason: %s", imageLocation, e.getMessage());
					}
                });
            }

            executor.shutdown();
            try {
                boolean finished = executor.awaitTermination(1, TimeUnit.HOURS);
                if (!finished) {
                	LoggingHelper.error().log("Timeout: some containers did not finish in time.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LoggingHelper.error().log("Execution interrupted.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
