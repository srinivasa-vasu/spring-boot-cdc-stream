package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Reviews;
import io.cdc.stream.service.ReviewService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReviewHandler extends TableHandler<Reviews> {

	private final ReviewService service;
	private final RetryTemplate retryTemplate;

	public ReviewHandler(ReviewService service, ConsumerConfig config, RetryTemplate retryTemplate) {
		super(config);
		this.service = service;
		this.retryTemplate = retryTemplate;
	}

	@Override
	public void delete(Reviews review) {
		retryTemplate.execute(ctx -> {
			service.delete(review);
			return Optional.empty();
		});
	}

	@Override
	public void save(Reviews review) {
		retryTemplate.execute(ctx -> {
			service.save(review);
			return Optional.empty();
		});
	}

	@Override
	public void save(List<Reviews> reviews) {
		retryTemplate.execute(ctx -> {
			service.save(reviews);
			return Optional.empty();
		});
	}

	@Override
	public void delete(List<Reviews> reviews) {
		retryTemplate.execute(ctx -> {
			service.delete(reviews);
			return Optional.empty();
		});
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
