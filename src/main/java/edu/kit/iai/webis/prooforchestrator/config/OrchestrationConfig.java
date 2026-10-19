/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestrationConfig {
    /**
     * Name of the nameSpace of the cluster
     */
    @Value("${proof.orchestration.k8s.namespace:#{null}}")
    private Optional<String> nameSpace;

    /**
     * The name of the rabbitMQ host
     */
    @Value("${spring.rabbitmq.host}")
    private String rabbitHost;
    /**
     * The port number for the rabbitMQ host
     */
    @Value("${spring.rabbitmq.port}")
    private String rabbitPort;
    /**
     * The user name for rabbitMQ host
     */
    @Value("${spring.rabbitmq.username}")
    private String rabbitUsername;
    /**
     * The password name for rabbitMQ host
     */
    @Value("${spring.rabbitmq.password}")
    private String rabbitPassword;
    /**
     * The virtual host for rabbitMQ
     */
    @Value("${spring.rabbitmq.virtual-host:#{null}}")
    private String rabbitVirtualHost;
    /**
     * The processing environment (LOCAL, DOCKER, KUBERNETES)
     */
    @Value("${proof.orchestration.processing:DOCKER}")
    private String processingEnvironment;
    /**
     * The shutdown timeout for the workers in seconds, default is 2 seconds
     */
    @Value("${proof.orchestration.shutdown.timeout:2}")
    private String shutdownTimeout;
    /**
     * the orchestrator logging level
     */
    @Value("${proof.orchestration.logLevel:INFO}")
    private String loggingLevel;

	/**
	 * the logging level for the workers.
	 * This environment variable is passed on to the start of the worker docker containers
	 */
	@Value("${proof.worker.logging.directory:/tmp/proof/logs}")
	private String workerLoggingDir;

   /**
    * Timeout used by the orchestrator while waiting for notify messages from workers.
    * The value is interpreted as seconds.
    */
   @Value("${proof.worker.timeout:${proof.orchestration.worker.timeout:10}}")
   private Long workerTimeout;

    /**
     * The PROOF workspace, the area where all data will be located to run a workflow. Default is '/tmp/proof'
     */
//    @Value("${proof.workspaceDir:/tmp/proof}")
    @Value("${proof.workspace.directory:/tmp/proof}")
    private String workspaceDir;

    /**
     * The attachments workspace, the area where all model files are located. Default is '/tmp/proof/attachments'
     */
    @Value("${proof.attachments.directory:/tmp/proof/attachments}")
    private String attachmentsDir;

    /**
     * The User workspace, the area where all user data will be located which is used in a workflow. Default is '/tmp/proof/userdata'
     */
    @Value("${proof.userdata.directory:/tmp/proof/userdata}")
    private String userdataDir;

    /**
     * Optional run script to be performed to start the workers  @TODO necessary?
     */
    @Value("${proof.orchestration.run.script:#{null}}")
    private Optional<String> runScript;

    /**
     * still used by KubvernetesService
     * @return
     */
    public Map<String, String> getRabbitAndElasticConfig() {
        return new HashMap<String, String>(Map.ofEntries(
                Map.entry("PYTHONUNBUFFERED", "1"),
                Map.entry("spring.rabbitmq.host", this.rabbitHost),
                Map.entry("spring.rabbitmq.port", this.rabbitPort),
                Map.entry("spring.rabbitmq.username", this.rabbitUsername),
                Map.entry("spring.rabbitmq.password", this.rabbitPassword)));
    }

  /**
   * Builds a worker environment map containing the configured timeout values.
   * The timeout is propagated under the orchestration key `proof.orchestration.worker.timeout` and interpreted as seconds.
   * @return a map with timeout-related worker configuration entries, or an empty map if disabled
   */
  public Map<String, String> getWorkerTimeoutConfig() {
		if (this.workerTimeout == null || this.workerTimeout <= 0) {
			return new HashMap<>();
		}

		return new HashMap<>(Map.ofEntries(
				Map.entry("proof.orchestration.worker.timeout", String.valueOf(this.workerTimeout))
		));
	}

    public Optional<String> getNameSpace() {
        return this.nameSpace;
    }

	public String getProcessingEnvironment() {
		return this.processingEnvironment;
	}

    public String getShutdownTimeout() { return this.shutdownTimeout; }

	public String getLoggingLevel() {
		return this.loggingLevel;
	}

	public String getRabbitHost() {
		return this.rabbitHost;
	}

	public String getWorkspaceDir() {
		return this.workspaceDir;
	}

	public String getAttachmentsDir() {
		return this.attachmentsDir;
	}

	public String getUserdataDir() {
		return this.userdataDir;
	}

	public String getWorkerLoggingDir() {
		return this.workerLoggingDir;
	}

  /**
   * Returns the configured worker timeout value.
   * The value is interpreted as seconds and converted to milliseconds where needed.
   *
   * @return the configured timeout in seconds, or {@code null} / non-positive when disabled
   */
  public Long getWorkerTimeout() {
    return this.workerTimeout;
  }
}
