/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.reactive.function.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.springframework.web.reactive.function.client.WebClient.builder;

@Configuration
public class ClientSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientSecurityConfig.class);

    /**
     * Configure oauth2 providers.
     *
     * @param token_uri     The token URI from the config.
     * @param client_id     Client ID URI from the config.
     * @param client_secret Client Secret from the config.
     * @return Configured registration repository.
     */
    @Bean
    public ReactiveClientRegistrationRepository getRegistration(
            @Value("${spring.security.oauth2.client.provider.keycloak.token-uri}") @NonNull final String token_uri,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") @NonNull final String client_id,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-secret}") @NonNull final String client_secret) {
        final var registration = ClientRegistration
                .withRegistrationId("keycloak")
                .tokenUri(token_uri)
                .clientId(client_id)
                .clientSecret(client_secret)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();
        return new InMemoryReactiveClientRegistrationRepository(registration);
    }

    /**
     * Configure client for data service communication.
     * This client handles authentication and token retrieval automatically.
     *
     * @param clientRegistrations Registrations from the manager.
     * @return Configured WebClient.
     */
    @Profile(value = {"prod"})
    @Bean(name = "defaultWebClient", value = "defaultWebClient")
    public WebClient defaultWebClient(@NonNull final ReactiveClientRegistrationRepository clientRegistrations) {
        log.info("Created default web client with auth");
        final var builder = builder();
        final var clientService = new InMemoryReactiveOAuth2AuthorizedClientService(clientRegistrations);
        final var authorizedClientManager = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(clientRegistrations, clientService);
        final var oauth = new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        oauth.setDefaultClientRegistrationId("keycloak");
        return builder
                .filter(oauth)
                .build();
    }

    /**
     * Configure client for data service communication.
     * This client handles authentication and token retrieval automatically.
     *
     * @return Configured WebClient.
     */
    @Profile(value = {"dev", "test", "debug"})
    @Bean(name = "defaultWebClient", value = "defaultWebClient")
    public WebClient devDefaultWebclient() {
        log.info("Created default web client without auth");
        final var builder = builder();
        return builder
                .build();
    }

}
