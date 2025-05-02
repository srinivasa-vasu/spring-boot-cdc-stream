package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Orders;
import io.cdc.stream.service.OrderService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderHandler extends TableHandler<Orders> {

	private final OrderService service;
	private final RetryTemplate retryTemplate;

	public OrderHandler(OrderService service, ConsumerConfig config, RetryTemplate retryTemplate) {
		super(config);
		this.service = service;
		this.retryTemplate = retryTemplate;
	}

	@Override
	public void delete(Orders order) {
		retryTemplate.execute(ctx -> {
			service.delete(order);
			return Optional.empty();
		});
	}

	@Override
	public void save(Orders order) {
		retryTemplate.execute(ctx -> {
			service.save(order);
			return Optional.empty();
		});
	}

	@Override
	public void save(List<Orders> orders) {
		retryTemplate.execute(ctx -> {
			service.save(orders);
			return Optional.empty();
		});
	}

	@Override
	public void delete(List<Orders> orders) {
		retryTemplate.execute(ctx -> {
			service.delete(orders);
			return Optional.empty();
		});
	}


	@Override
	public void handle(Struct payload) {
		handle(payload, Orders.class);
	}

	@Override
	public void handleQueue(Struct payload) {
		handleQueue(payload, Orders.class);
	}

	@PostConstruct
	public void processQueue() {
		executors.submit(() -> processQueue(Orders.class));
	}

}
