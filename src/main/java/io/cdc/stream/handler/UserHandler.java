package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Users;
import io.cdc.stream.service.UserService;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserHandler extends EntityHandler<Users> {

	public UserHandler(ConsumerConfig config, RetryTemplate retryTemplate, UserService service) {
		super(config, retryTemplate, service);
	}

	@Override
	public void handle(Struct payload) {
		handle(payload, Users.class);
	}

	@Override
	public void handleQueue(Struct payload) {
		handleQueue(payload, Users.class);
	}

	@PostConstruct
	public void processQueue() {
		executors.submit(() -> processQueue(Users.class));
	}

}
