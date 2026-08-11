/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.config.security;

import edu.kit.iai.webis.prooforchestrator.model.CorsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;


@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class ServerSecurityConfig {

    public static final String REALM_ACCESS = "realm_access";
    public static final String ROLE = "ROLE_";
    public static final String ROLES = "roles";
    public static final String GROUPS = "groups";

    @Bean
    public AuthenticationManager authenticationManager(final JwtDecoder jwtDecoder) {
        JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
        return new ProviderManager(jwtAuthenticationProvider);
    }

    /**
     * Configure the server security filter chain.
     *
     * @param http Object to be configured.
     * @return Configured filterChain.
     * @throws Exception On failure to configure filter chain.
     */
    @Bean
    @Profile(value = {"prod", "dev"})
    public SecurityFilterChain filterChain(@NonNull final HttpSecurity http, @Qualifier("corsProperties") final CorsProperties corsProperties) throws Exception {
        http
                .cors((final var cors) -> {
                    final var configuration = new CorsConfiguration();
                    final var source = new UrlBasedCorsConfigurationSource();
                    configuration.setAllowedOriginPatterns(corsProperties.getOrigins());
                    configuration.setAllowedMethods(corsProperties.getMethods());
                    configuration.setAllowedHeaders(corsProperties.getHeaders());
                    configuration.setAllowCredentials(true);
                    source.registerCorsConfiguration("/**", configuration);
                    cors.configurationSource(source);
                })
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests((final var authorize) -> {
                    authorize.requestMatchers("/swagger-ui/**").permitAll();
                    authorize.requestMatchers("/v3/api-docs/**").permitAll();
                    authorize.anyRequest().permitAll();
                })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer((final var oauth2) ->
                        oauth2.jwt((final var jwt) -> jwt
                                .jwtAuthenticationConverter((final var converter) -> {
                                    final var grantedAuthorities = new ArrayList<GrantedAuthority>();
                                    if (converter.hasClaim(REALM_ACCESS)) {
                                        final Map<String, Collection<String>> realmAccess = converter.getClaim(REALM_ACCESS);
                                        final Collection<String> roles = realmAccess.get(ROLES);
                                        roles.stream()
                                                .map((final var role) -> new SimpleGrantedAuthority(ROLE + role))
                                                .forEach(grantedAuthorities::add);
                                    }
                                    if (converter.hasClaim(GROUPS)) {
                                        final Collection<String> groups = converter.getClaim(GROUPS);
                                        groups.stream()
                                                .map((final var role) -> new SimpleGrantedAuthority(ROLE + role.replace("/", "")))
                                                .forEach(grantedAuthorities::add);
                                    }
                                    return new JwtAuthenticationToken(converter, grantedAuthorities);
                                }))
                );
        return http.build();
    }

    @Bean
    @Profile(value = {"test", "debug"})
    public SecurityFilterChain filterChainDev(@NonNull final HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Profile(value = {"test", "debug"})
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}