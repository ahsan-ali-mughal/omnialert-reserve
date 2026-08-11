package com.omnialert.reserve.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnialert.reserve.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Implements the first hop of PRD section 4.3 - Notification Pipeline Flow.
 *
 * Publishes an order-confirmed message to the AWS SNS topic. SNS fans this
 * out to the subscribed SQS queue (provisioned in localstack/init-aws.sh),
 * decoupling the notification concern from the persistence transaction so
 * a slow/unavailable downstream SMTP provider can never block order writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPublisherService {

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;

    @Value("${app.aws.sns-topic-arn}")
    private String topicArn;

    public void publishOrderConfirmation(Order order) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reservationId", order.getReservationId());
            payload.put("orderId", order.getId());
            payload.put("userId", order.getUserId());
            payload.put("itemId", order.getItemId());
            payload.put("quantity", order.getQuantity());
            payload.put("totalAmount", order.getTotalAmount());
            payload.put("status", order.getStatus().name());

            String message = objectMapper.writeValueAsString(payload);

            PublishRequest request = PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(message)
                    .subject("Order Confirmed: " + order.getReservationId())
                    .build();

            PublishResponse response = snsClient.publish(request);
            log.info("Published SNS notification for reservation {} (messageId={})",
                    order.getReservationId(), response.messageId());

        } catch (Exception ex) {
            // Notification failures must never roll back the already-committed order.
            log.error("Failed to publish SNS notification for reservation {}",
                    order.getReservationId(), ex);
        }
    }
}
