/*
 * Copyright (c) 2025
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics
 */

package edu.kit.iai.webis.prooforchestrator.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import edu.kit.iai.webis.prooforchestrator.config.OrchestrationConfig;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Workflow;

/**
 * Class to create property files and execute scripts for a local PROOF run based on X terminals
 */
@Service
public class ScriptGenerator {

    private final OrchestrationConfig orchestrationConfig;

//    @Value("${proof.orchestrator.run.propertyfile:proof-run-configurator-base.properties}")
    @Value("${proof.orchestrator.run.propertyfile:proof-run-configurator.properties}")
    private String runPropertyFileName;

    private String workspaceDir = null;

    private final static String RUN = "run_";
    private final static String LOG = "log_";
    private final static String CREATE_LOG_ZIP_FILE = "createLogZIPFile_";
    private final static String KEY_USE_TABS = "useTabs";
	private final static String KEY_PROOF_WORKER_DIR = "proof.worker.directory";
	private final static String KEY_PROOF_WORKER_LOGBACK_FILE = "proof.worker.logback.config";
	private final static String KEY_START_XTERM_POSITION_X = "startXLocation";
	private final static String KEY_START_XTERM_POSITION_Y = "startYLocation";
	private final static String KEY_DELTA_XTERM_POSITION_X = "deltaXLocation";
	private final static String KEY_DELTA_XTERM_POSITION_Y = "deltaYLocation";
	private final static String HOME_DIRECTORY = System.getProperty( "user.home" );

	private String executionId;
	private String scriptDirectoryName;
	private Properties configProperties = new Properties();
	private final List<String> runCommands = new ArrayList<String>();
    private final static Pattern BLOCK_NAME_PATTERN = Pattern.compile("([^A-Za-z\\d-._]+)");

	private final static boolean OS_WINDOWS = System.getProperty("os.name").startsWith("Windows");

	private final static String RUN_HEADER = (OS_WINDOWS ? "" : "#!/bin/bash\n#\n") + "# automatically generated run script for PROOF workers at %s\n#\n" ;
	private final static String LOG_HEADER = (OS_WINDOWS ? "" : "#!/bin/bash\n#\n") + "# automatically generated log script for PROOF workers at %s\n#\n" ;


    public ScriptGenerator(final OrchestrationConfig orchestrationConfig) {
    	this.orchestrationConfig = orchestrationConfig;
        LoggingHelper.debug().log("Configs for run script generation: \n%s", this.configProperties);
        try {
			this.configProperties.load(this.getClass().getClassLoader().getResourceAsStream("script-generator.properties"));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
        this.addValues();
    }

    private void addValues() {
    	this.configProperties.put("proof.worker.logging.logDirectory", this.orchestrationConfig.getWorkerLoggingDir());
    	this.configProperties.put("proof.workspace.directory", this.orchestrationConfig.getWorkspaceDir());
    	this.workspaceDir = this.orchestrationConfig.getWorkspaceDir();

    }

    public String createLogZIPScript( String executionId, Workflow workflow ) throws Exception {

    	this.runCommands.clear();
    	this.runCommands.add( LOG_HEADER.formatted(new Date()) );

		String mainLogScriptName = "";
    	String proofWorkspace = "";

    	try {
    		URI proofWorkspaceURI = new URI(this.workspaceDir);
    		proofWorkspace = new File(proofWorkspaceURI.getPath()).getAbsolutePath();
    		mainLogScriptName = proofWorkspace + File.separator + CREATE_LOG_ZIP_FILE + executionId;
    		this.createLogZIPFileCommands(workflow);
    		this.createRunScript(mainLogScriptName);
    		LoggingHelper.info().log("Main log ZIP script '" + mainLogScriptName + "' successfully created!");

    	} catch (URISyntaxException e) {
    		throw e;
    	} catch (IOException e) {
    		LoggingHelper.error().log("error writing to main log script '%s', reason: %s", proofWorkspace + File.separator + LOG + executionId, e.getMessage());
    		throw e;
    	}
    	return mainLogScriptName;

    }

    public String createLogScript( String executionId, Workflow workflow ) throws Exception {

    	this.runCommands.clear();
    	this.runCommands.add( LOG_HEADER.formatted(new Date()) );

    	//@TODO
		if( ! this.orchestrationConfig.getProcessingEnvironment().equals("LOCAL")
				&& this.orchestrationConfig.getWorkspaceDir().equals("/tmp") ) {
			this.workspaceDir = "/tmp/proof";
		}

    	String mainLogScriptName = "";
    	String proofWorkspace = "";

    	try {
    		URI proofWorkspaceURI = new URI(this.workspaceDir);
    		proofWorkspace = new File(proofWorkspaceURI.getPath()).getAbsolutePath();
    		mainLogScriptName = proofWorkspace + File.separator + LOG + executionId;
    		this.createLogCommands(workflow);
    		this.createRunScript(mainLogScriptName);
    		LoggingHelper.info().log("Main log script '" + mainLogScriptName + "' successfully created!");

    	} catch (URISyntaxException e) {
    		throw e;
    	} catch (IOException e) {
    		LoggingHelper.error().log("error writing to main log script '%s', reason: %s", proofWorkspace + File.separator + LOG + executionId, e.getMessage());
    		throw e;
    	}
    	return mainLogScriptName;

    }

    /**
     * create the run scripts for local execution
     * @param executionId the {@link ExecutionException} id
     * @param workflow the workflow that is started
     * @return the name of the main run script that starts all workers automatically
     */
    public String createRunFiles( String executionId, Workflow workflow ) throws Exception {
    	LoggingHelper.trace().log("RUN-CONF::  WS-DIR: '%s' ,  RUN-PROP-FILENAME: '%s'".formatted(this.workspaceDir, this.runPropertyFileName) );
		this.configProperties.load(new FileInputStream(new File(this.workspaceDir + File.separator + this.runPropertyFileName)));
    	this.executionId = executionId;

		this.runCommands.clear();
		this.runCommands.add( RUN_HEADER.formatted(new Date()) );

		String mainRunScriptName = "";
		String proofWorkspace = "";
		try {
			URI proofWorkspaceURI = new URI(this.workspaceDir);
			proofWorkspace = new File(proofWorkspaceURI.getPath()).getAbsolutePath();
			this.scriptDirectoryName = this.workspaceDir + File.separator + executionId;

			this.createRunPropertyFiles(workflow);
			mainRunScriptName = proofWorkspace + File.separator + RUN + executionId;
			this.createRunScript(mainRunScriptName);
			LoggingHelper.info().log("Main run script successfully created!");
		} catch (URISyntaxException e) {
			throw e;
		} catch (IOException e) {
			LoggingHelper.error().log("error writing to main run script '%s', reason: %s", proofWorkspace + File.separator + RUN + executionId, e.getMessage());
			throw e;
		}
		return mainRunScriptName;

    }

    private void createLogZIPFileCommands(Workflow workflow) {

    	StringJoiner sj = new StringJoiner(" ");
    	String[] blockIndexName = new String[1];
//    	this.runCommands.add( OS_WINDOWS ? "timeout 1s" : "sleep 1s");
    	try {
    		workflow.getBlocks().values().forEach(block -> {
    			blockIndexName[0] = block.getName().replaceAll("\\s", "") + "-" + block.getIndex();
    			try {
    				if( OS_WINDOWS ) {
    					this.runCommands.add("docker logs -f " + blockIndexName[0] + " > " + LOG + blockIndexName[0]);
    				}
    				else {
    					this.runCommands.add("docker logs -f " + blockIndexName[0] + " > " + LOG + blockIndexName[0]);
    				}
    				sj.add(LOG + blockIndexName[0]);
    			} catch (Exception e) {
    				e.printStackTrace();
    			}
    		});
    		this.runCommands.add("\ntar cvf " + LOG + blockIndexName[0] + ".tar " + sj.toString());
    	} catch (Exception e) {
    		e.printStackTrace();
    		LoggingHelper.error().log("ERROR creating log terminal (run file) for each block, reason: %s", e.getMessage());
    	}
    }

    private void createLogCommands(Workflow workflow) {

    	// the x position of the first xterm window (block) when no tabs are used, default = 0
    	int x = Integer.parseInt(this.configProperties.getProperty(KEY_START_XTERM_POSITION_X, "0"));
    	// the y position of the first xterm window (block) when no tabs are used, default = 0
    	int y = Integer.parseInt(this.configProperties.getProperty(KEY_START_XTERM_POSITION_Y, "0"));
    	// the x distance of the next block xterm window, default = 50
    	int deltaX = Integer.parseInt(this.configProperties.getProperty(KEY_DELTA_XTERM_POSITION_X, "50"));
    	// the y distance of the next block xterm window, default = 50
    	int deltaY = Integer.parseInt(this.configProperties.getProperty(KEY_DELTA_XTERM_POSITION_Y, "50"));

    	final int[] loc = new int[] { x, y };

    	final boolean useTabs = Boolean.parseBoolean( this.configProperties.getProperty(KEY_USE_TABS, "false") );

    	this.runCommands.add( OS_WINDOWS ? "timeout 1s" : "sleep 1s");
    	try {
    		workflow.getBlocks().values().forEach(block -> {
    			String blockIndexName = block.getName().replaceAll("\\s", "") + "-" + block.getIndex();
    			try {
    				if( OS_WINDOWS ) {
    					this.runCommands.add("docker logs -f " + blockIndexName + " /WAIT");
    				}
    				else {
    					this.runCommands.add("xfce4-terminal " + (useTabs ? " --tab" : "" )
    							+ " -e 'bash -c \"docker logs -f " + blockIndexName + "\"' -T "
    							+ blockIndexName + "_LOG  --geometry=300x40+" + loc[0] + "+" + loc[1] + " --hold");
    					loc[0]+= deltaX;
    					loc[1]+= deltaY;
    				}
    			} catch (Exception e) {
    				e.printStackTrace();
    			}
    		});

    	} catch (Exception e) {
    		e.printStackTrace();
    		LoggingHelper.error().log("ERROR creating log terminal (run file) for each block, reason: %s", e.getMessage());
    	}
    }

	private void createRunPropertyFiles(Workflow workflow) {

		// the x position of the first xterm window (block) when no tabs are used, default = 0
		int x = Integer.parseInt(this.configProperties.getProperty(KEY_START_XTERM_POSITION_X, "0"));
		// the y position of the first xterm window (block) when no tabs are used, default = 0
		int y = Integer.parseInt(this.configProperties.getProperty(KEY_START_XTERM_POSITION_Y, "0"));
		// the x distance of the next block xterm window, default = 50
		int deltaX = Integer.parseInt(this.configProperties.getProperty(KEY_DELTA_XTERM_POSITION_X, "50"));
		// the y distance of the next block xterm window, default = 50
		int deltaY = Integer.parseInt(this.configProperties.getProperty(KEY_DELTA_XTERM_POSITION_Y, "50"));

		final int[] loc = new int[] { x, y };

		File outputDirectory = new File(this.scriptDirectoryName);
		LoggingHelper.debug().log("Directory for run scripts: '%s'", this.scriptDirectoryName);
		if( ! outputDirectory.exists() ) {
			try {
				outputDirectory.mkdirs();
				LoggingHelper.info().log("Directory for run scripts: '%s' created ...", this.scriptDirectoryName);
			} catch (SecurityException e) {
				e.printStackTrace();
			}
		}

    	final boolean useTabs = Boolean.parseBoolean( this.configProperties.getProperty(KEY_USE_TABS, "false") );
		try {
			final URI runPropertyURI = this.createApplicationPropertyFile();
			workflow.getBlocks().values().forEach(block -> {

				String mvnScriptName = this.createMavenStartScript( workflow.getId(), block, runPropertyURI);
//				String proofWorkerInstallDir = this.configProperties.getProperty(KEY_PROOF_WORKER_DIR);
				URI proofWorkerInstallDirURI;
				try {
					proofWorkerInstallDirURI = new URI( this.configProperties.getProperty(KEY_PROOF_WORKER_DIR) );
					if( OS_WINDOWS ) {
						this.runCommands.add("cd " + new File(proofWorkerInstallDirURI.getPath()) + " && start \""
								+ this.validateName(block.getName())  + "-" + block.getIndex() +"\" "
								+ mvnScriptName + " /WAIT");
						this.runCommands.add("timeout 1s");
					}
					else {
						this.runCommands.add("cd " + new File(proofWorkerInstallDirURI.getPath()) + " && xfce4-terminal " + (useTabs ? " --tab" : "" )
								+ " -e 'bash -c " + mvnScriptName + "' -T " + this.validateName(block.getName()) + "-" + block.getIndex()
								+ (useTabs ? "" : ("  --geometry=300x40+" + loc[0] + "+" + loc[1]) ) + " --hold");
						this.runCommands.add("sleep 1s");
						loc[0]+= deltaX;
						loc[1]+= deltaY;
					}
				} catch (URISyntaxException e) {
					e.printStackTrace();
				}
			});

		} catch (Exception e) {
			e.printStackTrace();
			LoggingHelper.error().log("ERROR creating maven run files for each block, reason: %s", e.getMessage());
		}
	}

	private String createMavenStartScript(String workflowId, Block block, URI appPropURI) {

		final String logbackFile = this.configProperties.getProperty(KEY_PROOF_WORKER_LOGBACK_FILE);
		String script = "mvn spring-boot:run -Dspring-boot.run.arguments='--spring.config.location="
//				+ "\"file://" + appPropURI + (logbackFile != null ? "\" --logging.config=" + logbackFile : "")
				+ "\"" + appPropURI.toString() + "\"" + (logbackFile != null ? " --logging.config=" + logbackFile : "")
				+ " --proof.worker.workflow.executionId=" + this.executionId
				+ " --proof.worker.workflow.uuid=" + workflowId
				+ " --proof.worker.block.uuid=" + block.getId()
				+ " --proof.worker.block.id=" + block.getIndex()
				+ "'\n";		// "\" --debug'";

		String mavenStartScriptName = this.scriptDirectoryName + File.separator + "start_" + this.validateName(block.getName()) + "_" + block.getIndex();

        try {
			var path = Files.writeString(Path.of(mavenStartScriptName), script);
			// set execute permission
			Files.setPosixFilePermissions(path,
					Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		} catch (IOException e) {
			LoggingHelper.error().log("error writing to file '%s', reason: %s", mavenStartScriptName, e.getMessage());
		}

		return mavenStartScriptName;
	}

	private void createRunScript(String mainRunScriptName) throws IOException {
		try( FileWriter writer = new FileWriter(mainRunScriptName) ) {
			LoggingHelper.info().log("=> creating run config file '%s' for all Blocks ... ", mainRunScriptName);

			this.runCommands.forEach(r -> {
				try {
					writer.append( r ).append("\n");
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		}
		catch( IOException e ) {
//			LoggingHelper.error().log("error writing to file '%s', reason: %s", mainRunScriptName, e.getMessage());
			throw e;
		}
		Files.setPosixFilePermissions(Paths.get(mainRunScriptName),
				Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
	}


	private URI createApplicationPropertyFile() throws URISyntaxException {
//		final String appPropFilePath = this.scriptDirectoryName +  File.separator + "application.properties";
		URI appPropFileURI = new URI(this.scriptDirectoryName +  File.separator + "application.properties");
		try (FileWriter writer = new FileWriter(new File(appPropFileURI.getPath())))
		{
			LoggingHelper.info().log("=> creating application.properties file '%s' ...", appPropFileURI.getPath());
			this.configProperties.store(writer, "auto-generated");
		}
		catch( IOException e ) {
			LoggingHelper.error().log("error writing to file '%s', reason: %s", appPropFileURI.getPath(), e.getMessage());
		}

		return appPropFileURI;
	}

	private String validateName( String name ) {
		return BLOCK_NAME_PATTERN.matcher(name).replaceAll("_");
	}


}

