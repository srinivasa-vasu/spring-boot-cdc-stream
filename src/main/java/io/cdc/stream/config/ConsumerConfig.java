package io.cdc.stream.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.springframework.util.Assert.isTrue;

@Configuration
@ConfigurationProperties(prefix = "consumer")
@Setter
@Getter
public class ConsumerConfig {
	private int queueCapacity = 100;
	private int batchSize = 500;
	private boolean enableBatch;
	private int flushIntervalMs = 2000;
	private int drainIntervalMs = 60000;

	@PostConstruct
	void validate() {
		isTrue((enableBatch && queueCapacity >= batchSize), "Queue capacity must be greater than batch size");
		isTrue(batchSize <= 10000, "Batch size must be less than or equal to 10000");
		isTrue(flushIntervalMs > 50, "Flush interval must be greater than 50ms");
		isTrue(drainIntervalMs <= 180000, "Drain interval must be less than or equal to 180000");
	}
}
