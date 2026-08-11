package com.omnialert.reserve.service;

import com.omnialert.reserve.document.OrderAuditLog;
import com.omnialert.reserve.dto.OrderReservedEvent;
import com.omnialert.reserve.entity.Order;
import com.omnialert.reserve.repository.OrderAuditLogRepository;
import com.omnialert.reserve.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Implements PRD section 4.2 - Asynchronous Persistence Flow.
 *
 * Consumes OrderReservedEvent records from `order-reserved-events`,
 * writes an ACID-compliant row into MySQL via HikariCP-pooled JPA,
 * records an audit log entry in MongoDB, and hands off to
 * NotificationPublisherService to fan out the confirmation notification
 * (PRD section 4.3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPersistenceConsumer {

    private final OrderRepository orderRepository;
    private final OrderAuditLogRepository auditLogRepository;
    private final NotificationPublisherService notificationPublisherService;

    @KafkaListener(topics = "order-reserved-events", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onOrderReserved(ConsumerRecord<String, OrderReservedEvent> record) {
        OrderReservedEvent event = record.value();
        long start = System.nanoTime();

        try {
            if (orderRepository.findByReservationId(event.getReservationId()).isPresent()) {
                log.warn("Duplicate Kafka delivery for reservation {} - skipping insert", event.getReservationId());
                return;
            }

            BigDecimal totalAmount = event.getUnitPrice().multiply(BigDecimal.valueOf(event.getQuantity()));

            Order order = Order.builder()
                    .reservationId(event.getReservationId())
                    .userId(event.getUserId())
                    .itemId(event.getItemId())
                    .quantity(event.getQuantity())
                    .totalAmount(totalAmount)
                    .status(Order.OrderStatus.CONFIRMED)
                    .build();

            Order saved = orderRepository.save(order);

            double processingTimeMs = (System.nanoTime() - start) / 1_000_000.0;

            auditLogRepository.save(OrderAuditLog.builder()
                    .reservationId(event.getReservationId())
                    .orderId(saved.getId())
                    .userId(event.getUserId())
                    .itemId(event.getItemId())
                    .action("ORDER_PERSISTED_MYSQL")
                    .status("SUCCESS")
                    .metadata(Map.of(
                            "kafkaTopic", record.topic(),
                            "kafkaPartition", record.partition(),
                            "kafkaOffset", record.offset(),
                            "processingTimeMs", processingTimeMs
                    ))
                    .timestamp(Instant.now())
                    .build());

            log.info("Order persisted for reservation {} (orderId={}, {}ms)",
                    event.getReservationId(), saved.getId(), processingTimeMs);

            notificationPublisherService.publishOrderConfirmation(saved);

        } catch (Exception ex) {
            log.error("Failed to persist order for reservation {}", event.getReservationId(), ex);
            auditLogRepository.save(OrderAuditLog.builder()
                    .reservationId(event.getReservationId())
                    .userId(event.getUserId())
                    .itemId(event.getItemId())
                    .action("ORDER_PERSISTED_MYSQL")
                    .status("FAILURE")
                    .metadata(Map.of("error", String.valueOf(ex.getMessage())))
                    .timestamp(Instant.now())
                    .build());
            // Rethrow so the DefaultErrorHandler / retry backoff configured in KafkaConfig applies.
            throw ex;
        }
    }
}
