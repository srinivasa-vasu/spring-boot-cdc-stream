package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Products;
import io.cdc.stream.service.PersistentService;
import io.cdc.stream.service.ProductService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProductHandler extends TableHandler<Products> {

	private final PersistentService<Products> service;

	private final RetryTemplate retryTemplate;

	public ProductHandler(ProductService service, ConsumerConfig config, RetryTemplate retryTemplate) {
		super(config);
		this.service = service;
		this.retryTemplate = retryTemplate;
	}

	@Override
	public void delete(Products product) {
		retryTemplate.execute(ctx -> {
			service.delete(product);
			return Optional.empty();
		});
	}

	@Override
	public void save(Products product) {
		retryTemplate.execute(ctx -> {
			service.save(product);
			return Optional.empty();
		});
	}

	@Override
	public void save(List<Products> products) {
		retryTemplate.execute(ctx -> {
			service.save(products);
			return Optional.empty();
		});
	}

	@Override
	public void delete(List<Products> products) {
		retryTemplate.execute(ctx -> {
			service.delete(products);
			return Optional.empty();
		});
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
