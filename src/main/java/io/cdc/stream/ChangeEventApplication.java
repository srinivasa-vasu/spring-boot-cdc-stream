package io.cdc.stream;

import io.cdc.stream.aspect.PersistentRetryPolicy;
import io.cdc.stream.config.RetryConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;

@SpringBootApplication
@EnableRetry
public class ChangeEventApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChangeEventApplication.class, args);
	}

	@Bean
	public RetryTemplate retryTemplate(PersistentRetryPolicy retryPolicy, RetryConfig retryConfig) {
		RetryTemplate retryTemplate = new RetryTemplate();
		ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
		backOffPolicy.setMaxInterval(retryConfig.getMaxIntervalInMs());
		backOffPolicy.setInitialInterval(retryConfig.getInitialIntervalInMs());
		backOffPolicy.setMultiplier(retryConfig.getMultiplier());
		retryTemplate.setRetryPolicy(retryPolicy);
		retryTemplate.setBackOffPolicy(backOffPolicy);
		return retryTemplate;
	}

}
