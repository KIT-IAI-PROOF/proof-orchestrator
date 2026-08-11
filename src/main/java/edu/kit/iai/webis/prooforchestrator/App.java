/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.OAuthScope;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@OpenAPIDefinition(
        info = @Info(
                title = "PROOF Orchestrator",
                description = "PROOF Orchestrator",
                version = "1.0.0",
                contact = @Contact(name = "KIT")
        ),
        servers = @Server(
                description = "Local",
                url = "http://localhost:8200")
)
@SecurityScheme(
        name = "oauth2",
        type = SecuritySchemeType.OAUTH2,
        flows = @OAuthFlows(
                clientCredentials = @OAuthFlow(
                        tokenUrl = "${spring.security.oauth2.client.provider.keycloak.token-uri}",
                        scopes = {@OAuthScope(name = "openid", description = "openid scope")})))
@ComponentScan(basePackages = {"edu.kit.iai.webis.prooforchestrator", "edu.kit.iai.webis.proofutils"})
public class App {

    public static void main(final String... args) throws ConfigurationException {
        SpringApplication.run(App.class, args);
    }

}
