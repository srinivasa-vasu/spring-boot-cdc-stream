package io.cdc.stream.event;

import io.cdc.stream.entity.Orders;
import io.cdc.stream.entity.Products;
import io.cdc.stream.entity.Reviews;
import io.cdc.stream.entity.Users;
import io.cdc.stream.handler.OrderHandler;
import io.cdc.stream.handler.ProductHandler;
import io.cdc.stream.handler.ReviewHandler;
import io.cdc.stream.handler.UserHandler;
import jakarta.annotation.PostConstruct;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

@Component
public class ChangeEventHandlerRegistry implements ApplicationContextAware {

	private final ChangeEventDispatcher dispatcher;

	private static ApplicationContext context;

	public ChangeEventHandlerRegistry(ChangeEventDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@PostConstruct
	public void registerHandlers() {
		dispatcher.registerHandler(Products.class.getSimpleName().toLowerCase(), context.getBean(ProductHandler.class));
		dispatcher.registerHandler(Users.class.getSimpleName().toLowerCase(), context.getBean(UserHandler.class));
		dispatcher.registerHandler(Orders.class.getSimpleName().toLowerCase(), context.getBean(OrderHandler.class));
		dispatcher.registerHandler(Reviews.class.getSimpleName().toLowerCase(), context.getBean(ReviewHandler.class));
	}

	@Override
	public void setApplicationContext(@NonNull ApplicationContext ac) throws BeansException {
		context = ac;
	}

}
