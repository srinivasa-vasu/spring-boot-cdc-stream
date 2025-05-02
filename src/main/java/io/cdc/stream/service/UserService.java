package io.cdc.stream.service;

import io.cdc.stream.entity.Users;
import io.cdc.stream.repository.UserRepository;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService implements PersistentService<Users> {

	private final UserRepository repository;

	public UserService(UserRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void delete(Users user) {
		repository.delete(user);
	}

	@Transactional
	public void save(Users user) {
		repository.save(user);
	}

	@Transactional
	public void save(List<Users> users) {
		repository.saveAll(users);
	}

	@Transactional
	public void delete(List<Users> users) {
		repository.deleteAll(users);
	}

}
