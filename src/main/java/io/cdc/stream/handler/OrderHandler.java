package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Orders;
import io.cdc.stream.service.OrderService;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderHandler extends EntityHandler<Orders> {

	public OrderHandler(ConsumerConfig config, RetryTemplate retryTemplate, OrderService service) {
		super(config, retryTemplate, service);
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
