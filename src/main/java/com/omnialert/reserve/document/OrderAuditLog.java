package com.omnialert.reserve.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "order_audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderAuditLog {

    @Id
    private String id;

    private String reservationId;
    private Long orderId;
    private Long userId;
    private Long itemId;

    /** e.g. ORDER_PERSISTED_MYSQL, ORDER_PERSISTENCE_FAILED */
    private String action;

    /** SUCCESS | FAILURE */
    private String status;

    private Map<String, Object> metadata;

    private Instant timestamp;
}
