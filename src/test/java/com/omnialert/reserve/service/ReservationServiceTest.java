package com.omnialert.reserve.service;

import com.omnialert.reserve.dto.OrderRequest;
import com.omnialert.reserve.entity.Item;
import com.omnialert.reserve.exception.DuplicatePurchaseException;
import com.omnialert.reserve.exception.InsufficientStockException;
import com.omnialert.reserve.exception.ItemNotFoundException;
import com.omnialert.reserve.repository.ItemRepository;
import com.omnialert.reserve.repository.OrderAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Verifies ReservationService correctly interprets each return code
 * from reserve_stock.lua (see scripts/reserve_stock.lua for the contract)
 * without requiring a live Redis instance.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private DefaultRedisScript<Long> reserveStockScript;
    @Mock private KafkaTemplate<String, com.omnialert.reserve.dto.OrderReservedEvent> kafkaTemplate;
    @Mock private ItemRepository itemRepository;
    @Mock private OrderAuditLogRepository auditLogRepository;

    @InjectMocks
    private ReservationService reservationService;

    private Item sampleItem;

    @BeforeEach
    void setUp() {
        sampleItem = Item.builder()
                .id(1001L)
                .title("Limited Edition Sneaker")
                .totalStock(100)
                .price(new BigDecimal("129.99"))
                .status("ACTIVE")
                .build();
    }

    @Test
    void reserve_success_publishesKafkaEventAndReturnsAccepted() {
        when(itemRepository.findById(1001L)).thenReturn(Optional.of(sampleItem));
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        var response = reservationService.reserve(new OrderRequest(8832L, 1001L, 2));

        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
        assertThat(response.getReservationId()).startsWith("res_");
    }

    @Test
    void reserve_insufficientStock_throws() {
        when(itemRepository.findById(1001L)).thenReturn(Optional.of(sampleItem));
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString(), anyString()))
                .thenReturn(-1L);

        assertThatThrownBy(() -> reservationService.reserve(new OrderRequest(8832L, 1001L, 999)))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void reserve_duplicatePurchase_throws() {
        when(itemRepository.findById(1001L)).thenReturn(Optional.of(sampleItem));
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString(), anyString()))
                .thenReturn(-2L);

        assertThatThrownBy(() -> reservationService.reserve(new OrderRequest(8832L, 1001L, 1)))
                .isInstanceOf(DuplicatePurchaseException.class);
    }

    @Test
    void reserve_itemNotInRedis_throws() {
        when(itemRepository.findById(1001L)).thenReturn(Optional.of(sampleItem));
        when(redisTemplate.execute(eq(reserveStockScript), anyList(), anyString(), anyString()))
                .thenReturn(-3L);

        assertThatThrownBy(() -> reservationService.reserve(new OrderRequest(8832L, 1001L, 1)))
                .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    void reserve_unknownItemInMysql_throwsBeforeTouchingRedis() {
        when(itemRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(new OrderRequest(8832L, 9999L, 1)))
                .isInstanceOf(ItemNotFoundException.class);
    }
}
