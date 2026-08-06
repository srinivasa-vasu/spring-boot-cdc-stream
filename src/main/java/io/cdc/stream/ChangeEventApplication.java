package io.cdc.stream;

import io.cdc.stream.aspect.YBRetryPolicy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.retry.RetryTemplate;

/**
 * Retry is used programmatically through the {@link RetryTemplate} below, so
 * {@code @EnableRetry} is not needed. Re-adding it — or any {@code @Retryable} method —
 * requires {@code spring-boot-starter-aop} on the classpath for AspectJ's annotations.
 */
@SpringBootApplication
public class ChangeEventApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChangeEventApplication.class, args);
	}

	@Bean
	public RetryTemplate retryTemplate(YBRetryPolicy retryPolicy) {
		RetryTemplate retryTemplate = new RetryTemplate();
		retryTemplate.setRetryPolicy(retryPolicy);
		return retryTemplate;
	}
}
