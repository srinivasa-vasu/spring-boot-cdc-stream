package io.cdc.stream.service;

import io.cdc.stream.entity.Orders;
import io.cdc.stream.repository.OrderRepository;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService implements PersistentService<Orders> {

	private final OrderRepository repository;

	public OrderService(OrderRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void delete(Orders order) {
		repository.delete(order);
	}

	@Transactional
	public void save(Orders order) {
		repository.save(order);
	}

	@Transactional
	public void save(List<Orders> orders) {
		repository.saveAll(orders);
	}

	@Transactional
	public void delete(List<Orders> orders) {
		repository.deleteAll(orders);
	}

}
