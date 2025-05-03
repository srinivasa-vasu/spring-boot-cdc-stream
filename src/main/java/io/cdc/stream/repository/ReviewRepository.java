package io.cdc.stream.repository;

import io.cdc.stream.entity.Reviews;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Reviews, Long> {

}
