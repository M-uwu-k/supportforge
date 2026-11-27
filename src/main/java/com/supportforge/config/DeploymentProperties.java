package com.supportforge.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;


@Validated
@ConfigurationProperties(prefix = "supportforge.deployment")
public record DeploymentProperties(
        @NotBlank String environment,
        @NotBlank String instanceId
) {
}

