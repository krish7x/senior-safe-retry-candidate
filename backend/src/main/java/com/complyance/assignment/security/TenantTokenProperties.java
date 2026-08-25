package com.complyance.assignment.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "assignment.auth")
public record TenantTokenProperties(Map<String, String> tokens) {

    public TenantTokenProperties {
        tokens = tokens == null ? Map.of() : Map.copyOf(tokens);
    }
}
