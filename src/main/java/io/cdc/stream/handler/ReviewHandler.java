package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Reviews;
import io.cdc.stream.service.ReviewService;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReviewHandler extends EntityHandler<Reviews> {

	public ReviewHandler(ConsumerConfig config, RetryTemplate retryTemplate, ReviewService service) {
		super(config, retryTemplate, service);
	}

	@Override
	public void handle(Struct payload) {
		handle(payload, Reviews.class);
	}

	@Override
	public void handleQueue(Struct payload) {
		handleQueue(payload, Reviews.class);
	}

	@PostConstruct
	public void processQueue() {
		executors.submit(() -> processQueue(Reviews.class));
	}

}
