package com.omnialert.reserve.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "notification_telemetry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTelemetry {

    @Id
    private String id;

    private String reservationId;

    /** EMAIL | SMS | PUSH */
    private String channel;

    private String recipient;
    private String snsMessageId;
    private String sqsMessageId;

    /** SENT | DELIVERED | FAILED | DEAD_LETTERED */
    private String deliveryStatus;

    private Integer attempts;
    private String providerResponse;

    private Instant timestamp;
}
