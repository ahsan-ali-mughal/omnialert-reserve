package com.omnialert.reserve.controller;

import com.omnialert.reserve.dto.OrderRequest;
import com.omnialert.reserve.dto.OrderResponse;
import com.omnialert.reserve.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final ReservationService reservationService;

    /**
     * PRD 4.1 - Edge Reservation Flow.
     * Validates stock atomically in Redis and returns immediately with
     * 202 ACCEPTED; durable persistence and notification happen async.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse response = reservationService.reserve(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
