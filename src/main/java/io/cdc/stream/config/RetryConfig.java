package io.cdc.stream.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "retry")
@Getter
@Setter
public class RetryConfig {

	private int maxAttempts = 3;

	private int initialIntervalInMs = 200;

	private double multiplier = 2;

	private int maxIntervalInMs = 10000;

}
