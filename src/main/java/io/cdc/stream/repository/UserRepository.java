package io.cdc.stream.repository;

import io.cdc.stream.entity.Products;
import io.cdc.stream.entity.Users;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<Users, Long> {
}
