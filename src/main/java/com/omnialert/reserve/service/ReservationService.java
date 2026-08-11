package com.omnialert.reserve.service;

import com.omnialert.reserve.document.OrderAuditLog;
import com.omnialert.reserve.dto.OrderReservedEvent;
import com.omnialert.reserve.dto.OrderRequest;
import com.omnialert.reserve.dto.OrderResponse;
import com.omnialert.reserve.entity.Item;
import com.omnialert.reserve.exception.DuplicatePurchaseException;
import com.omnialert.reserve.exception.InsufficientStockException;
import com.omnialert.reserve.exception.ItemNotFoundException;
import com.omnialert.reserve.repository.ItemRepository;
import com.omnialert.reserve.repository.OrderAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements PRD section 4.1 - Edge Reservation Flow (Synchronous).
 *
 * Runs the atomic Redis Lua script to validate + deduct stock and record
 * the purchasing user in a tracking set, all in sub-millisecond time at
 * the cache edge, with zero possibility of overselling due to Lua's
 * single-threaded atomic execution inside Redis.
 *
 * On success, publishes an OrderReservedEvent to Kafka so a downstream
 * worker can perform the durable MySQL write asynchronously, keeping the
 * synchronous critical path free of database lock contention.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final int SUCCESS = 1;
    private static final int INSUFFICIENT_STOCK = -1;
    private static final int DUPLICATE_PURCHASE = -2;
    private static final int ITEM_NOT_FOUND = -3;

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> reserveStockScript;
    private final KafkaTemplate<String, OrderReservedEvent> kafkaTemplate;
    private final ItemRepository itemRepository;
    private final OrderAuditLogRepository auditLogRepository;

    public OrderResponse reserve(OrderRequest request) {
        Item item = itemRepository.findById(request.getItemId())
                .orElseThrow(() -> new ItemNotFoundException(
                        "Item " + request.getItemId() + " does not exist"));

        String stockKey = "inventory:item:" + request.getItemId();
        String usersKey = "inventory:item:" + request.getItemId() + ":users";

        List<String> keys = List.of(stockKey, usersKey);

        Long result = redisTemplate.execute(
                reserveStockScript,
                keys,
                String.valueOf(request.getUserId()),
                String.valueOf(request.getQuantity())
        );

        int code = result == null ? ITEM_NOT_FOUND : result.intValue();

        switch (code) {
            case SUCCESS -> {
                String reservationId = "res_" + UUID.randomUUID();
                publishReservedEvent(reservationId, request, item);
                log.info("Reservation {} accepted for user={} item={} qty={}",
                        reservationId, request.getUserId(), request.getItemId(), request.getQuantity());
                return OrderResponse.builder()
                        .reservationId(reservationId)
                        .status("ACCEPTED")
                        .message("Stock reserved. Order is being processed asynchronously.")
                        .build();
            }
            case INSUFFICIENT_STOCK -> throw new InsufficientStockException(
                    "Insufficient stock for item " + request.getItemId());
            case DUPLICATE_PURCHASE -> throw new DuplicatePurchaseException(
                    "User " + request.getUserId() + " has already reserved item " + request.getItemId());
            case ITEM_NOT_FOUND -> throw new ItemNotFoundException(
                    "No inventory record for item " + request.getItemId() + " in Redis");
            default -> throw new IllegalStateException("Unexpected Lua script result: " + code);
        }
    }

    private void publishReservedEvent(String reservationId, OrderRequest request, Item item) {
        OrderReservedEvent event = new OrderReservedEvent(
                reservationId,
                request.getUserId(),
                request.getItemId(),
                request.getQuantity(),
                item.getPrice(),
                Instant.now()
        );

        kafkaTemplate.send("order-reserved-events", reservationId, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish OrderReservedEvent for {}", reservationId, ex);
                        auditLogRepository.save(OrderAuditLog.builder()
                                .reservationId(reservationId)
                                .userId(request.getUserId())
                                .itemId(request.getItemId())
                                .action("KAFKA_PUBLISH_FAILED")
                                .status("FAILURE")
                                .metadata(Map.of("error", String.valueOf(ex.getMessage())))
                                .timestamp(Instant.now())
                                .build());
                    } else {
                        log.debug("Published OrderReservedEvent {} to partition {} offset {}",
                                reservationId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    /** Convenience for tests / bootstrap: seed a stock key + reset the reservation set. */
    public void seedStock(Long itemId, int stock) {
        redisTemplate.opsForValue().set("inventory:item:" + itemId, String.valueOf(stock));
        redisTemplate.delete("inventory:item:" + itemId + ":users");
    }
}
