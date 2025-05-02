package io.cdc.stream.repository;

import io.cdc.stream.entity.Orders;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Orders, Long> {
}
