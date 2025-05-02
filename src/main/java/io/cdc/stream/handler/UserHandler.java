package io.cdc.stream.handler;

import io.cdc.stream.config.ConsumerConfig;
import io.cdc.stream.entity.Users;
import io.cdc.stream.service.UserService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.connect.data.Struct;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserHandler extends TableHandler<Users> {

	private final UserService service;
	private final RetryTemplate retryTemplate;

	public UserHandler(UserService service, ConsumerConfig config, RetryTemplate retryTemplate) {
		super(config);
		this.service = service;
		this.retryTemplate = retryTemplate;
	}

	@Override
	public void delete(Users user) {
		retryTemplate.execute(ctx -> {
			service.delete(user);
			return Optional.empty();
		});
	}

	@Override
	public void save(Users user) {
		retryTemplate.execute(ctx -> {
			service.save(user);
			return Optional.empty();
		});
	}

	@Override
	public void save(List<Users> users) {
		retryTemplate.execute(ctx -> {
			service.save(users);
			return Optional.empty();
		});
	}

	@Override
	public void delete(List<Users> users) {
		retryTemplate.execute(ctx -> {
			service.delete(users);
			return Optional.empty();
		});
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
