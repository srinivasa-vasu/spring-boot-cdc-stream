package io.cdc.stream.service;

import io.cdc.stream.entity.Products;
import io.cdc.stream.repository.ProductRepository;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService implements PersistentService<Products> {

	private final ProductRepository repository;

	public ProductService(ProductRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void delete(Products product) {
		repository.delete(product);
	}

	@Transactional
	public void save(Products product) {
		repository.save(product);
	}

	@Transactional
	public void save(List<Products> products) {
		repository.saveAll(products);
	}

	@Transactional
	public void delete(List<Products> products) {
		repository.deleteAll(products);
	}

}
