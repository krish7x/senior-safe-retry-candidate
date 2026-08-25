package com.complyance.assignment;

import com.complyance.assignment.security.TenantTokenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(TenantTokenProperties.class)
public class SafeRetryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafeRetryApplication.class, args);
    }
}
