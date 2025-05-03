package io.cdc.stream.repository;

import io.cdc.stream.entity.Products;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Products, Long> {

}
