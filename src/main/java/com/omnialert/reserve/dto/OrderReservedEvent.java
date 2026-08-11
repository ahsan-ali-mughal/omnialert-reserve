package com.omnialert.reserve.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published to Kafka topic `order-reserved-events` immediately after
 * the Redis Lua script confirms a successful stock reservation.
 * Consumed by OrderPersistenceConsumer to perform the durable MySQL write.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderReservedEvent implements Serializable {
    private String reservationId;
    private Long userId;
    private Long itemId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private Instant reservedAt;
}
