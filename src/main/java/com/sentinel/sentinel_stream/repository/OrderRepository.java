package com.sentinel.sentinel_stream.repository;

import com.sentinel.sentinel_stream.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    // JpaRepository provides built-in methods such as save(), findAll(), and findById().
    // No custom SQL queries are required for standard CRUD operations at this stage.
}
