package io.cdc.stream.service;

import io.cdc.stream.entity.Reviews;
import io.cdc.stream.repository.ReviewRepository;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService implements PersistentService<Reviews> {

	private final ReviewRepository repository;

	public ReviewService(ReviewRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void delete(Reviews review) {
		repository.delete(review);
	}

	@Transactional
	public void save(Reviews review) {
		repository.save(review);
	}

	@Transactional
	public void save(List<Reviews> reviews) {
		repository.saveAll(reviews);
	}

	@Transactional
	public void delete(List<Reviews> reviews) {
		repository.deleteAll(reviews);
	}

}
