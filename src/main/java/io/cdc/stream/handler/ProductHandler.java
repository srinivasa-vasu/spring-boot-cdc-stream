package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Products;
import io.cdc.stream.service.ProductService;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProductHandler extends EntityHandler<Products> {

	public ProductHandler(ConsumerConfig config, RetryTemplate retryTemplate, ProductService service) {
		super(config, retryTemplate, service);
	}

	@Override
	public void handle(Struct payload) {
		handle(payload, Products.class);
	}

	@Override
	public void handleQueue(Struct payload) {
		handleQueue(payload, Products.class);
	}

	// @Scheduled(fixedDelay = 1000)
	@PostConstruct
	public void processQueue() {
		executors.submit(() -> processQueue(Products.class));
	}

}
