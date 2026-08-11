package com.omnialert.reserve.repository;

import com.omnialert.reserve.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByReservationId(String reservationId);
}
