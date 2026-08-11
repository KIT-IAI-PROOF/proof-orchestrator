/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.prooforchestrator.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Schema
@Configuration
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    private final List<String> methods = new ArrayList<>();
    private final List<String> origins = new ArrayList<>();
    private final List<String> headers = new ArrayList<>();

    CorsProperties() {
    }

    public static CorsPropertiesBuilder builder() {
        return new CorsPropertiesBuilder();
    }

    public List<String> getMethods() {
        return this.methods;
    }

    public List<String> getOrigins() {
        return this.origins;
    }

    public List<String> getHeaders() {
        return this.headers;
    }

    public String toString() {
        return "CorsProperties(methods=" + this.getMethods() + ", origins=" + this.getOrigins() + ", headers=" + this.getHeaders() + ")";
    }

    public static class CorsPropertiesBuilder {
        CorsPropertiesBuilder() {
        }

        public CorsProperties build() {
            return new CorsProperties();
        }

        public String toString() {
            return "CorsProperties.CorsPropertiesBuilder()";
        }
    }
}