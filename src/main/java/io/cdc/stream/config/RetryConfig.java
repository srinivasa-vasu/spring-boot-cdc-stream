package io.cdc.stream.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "spring.retry")
@Getter
@Setter
public class RetryConfig {

	private int maxInterval;
	private int initialInterval;
	private int multiplier;
	private int maxAttempts;
	private int jitter;

}
