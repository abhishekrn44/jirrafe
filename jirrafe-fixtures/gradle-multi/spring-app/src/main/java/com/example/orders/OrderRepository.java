package com.example.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByStatus(String status);

    @Query("select o from Order o where o.status = 'OPEN' and o.id > :since")
    List<Order> openSince(long since);
}
