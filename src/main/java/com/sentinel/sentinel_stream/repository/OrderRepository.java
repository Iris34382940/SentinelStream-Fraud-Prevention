package com.sentinel.sentinel_stream.repository;

import com.sentinel.sentinel_stream.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    // JpaRepository 內建了 save, findAll, findById 等功能，暫時不用自己寫 SQL
}

