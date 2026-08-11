package com.omnialert.reserve.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnialert.reserve.document.NotificationTelemetry;
import com.omnialert.reserve.repository.NotificationTelemetryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Implements the second hop of PRD section 4.3 - Notification Pipeline Flow.
 *
 * Polls the SQS queue bound to the SNS topic, dispatches the confirmation
 * email via JavaMailSender, and logs delivery metrics to MongoDB
 * (notification_telemetry). A Redis lock key (lock:notification:{idempotencyKey},
 * TTL 10m per PRD section 5.1) prevents double-sending if a message is
 * redelivered before it's removed from the queue. If SMTP fails past
 * max receive count, SQS's configured redrive policy automatically routes
 * the message to the Dead Letter Queue (see localstack/init-aws.sh).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailListenerService {

    private static final int MAX_MESSAGES_PER_POLL = 10;
    private static final int WAIT_TIME_SECONDS = 5;
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final SqsClient sqsClient;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final NotificationTelemetryRepository telemetryRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.aws.sqs-queue-url}")
    private String queueUrl;

    @Value("${spring.mail.username:no-reply@omnialert.local}")
    private String fromAddress;

    @PostConstruct
    public void logStartup() {
        log.info("EmailListenerService polling queue: {}", queueUrl);
    }

    /**
     * Long-polls SQS every 5 seconds. In a production deployment this would
     * typically be replaced with spring-cloud-aws's managed @SqsListener,
     * but a manual poll loop keeps this project dependency-light and makes
     * the DLQ / idempotency-lock behavior explicit for reviewers.
     */
    @Scheduled(fixedDelay = 5000)
    public void pollQueue() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(MAX_MESSAGES_PER_POLL)
                .waitTimeSeconds(WAIT_TIME_SECONDS)
                .build();

        List<Message> messages;
        try {
            messages = sqsClient.receiveMessage(request).messages();
        } catch (Exception ex) {
            log.error("Failed to poll SQS queue {}", queueUrl, ex);
            return;
        }

        for (Message message : messages) {
            handleMessage(message);
        }
    }

    private void handleMessage(Message message) {
        String idempotencyKey = message.messageId();
        String lockKey = "lock:notification:" + idempotencyKey;

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL);

        if (Boolean.FALSE.equals(acquired)) {
            log.warn("Notification {} already processed/in-flight, skipping duplicate delivery", idempotencyKey);
            deleteMessage(message);
            return;
        }

        String reservationId = "unknown";
        String recipient = fromAddress;

        try {
            // SNS wraps the original payload inside a "Message" field.
            JsonNode envelope = objectMapper.readTree(message.body());
            JsonNode payload = envelope.has("Message")
                    ? objectMapper.readTree(envelope.get("Message").asText())
                    : envelope;

            reservationId = payload.path("reservationId").asText("unknown");
            recipient = payload.path("recipient").asText(fromAddress);

            sendEmail(recipient, reservationId, payload.toString());

            telemetryRepository.save(NotificationTelemetry.builder()
                    .reservationId(reservationId)
                    .channel("EMAIL")
                    .recipient(recipient)
                    .sqsMessageId(message.messageId())
                    .deliveryStatus("DELIVERED")
                    .attempts(1)
                    .providerResponse("250 2.0.0 OK")
                    .timestamp(Instant.now())
                    .build());

            deleteMessage(message);
            log.info("Email dispatched for reservation {} to {}", reservationId, recipient);

        } catch (Exception ex) {
            log.error("Email dispatch failed for message {} (reservation {})",
                    message.messageId(), reservationId, ex);

            telemetryRepository.save(NotificationTelemetry.builder()
                    .reservationId(reservationId)
                    .channel("EMAIL")
                    .recipient(recipient)
                    .sqsMessageId(message.messageId())
                    .deliveryStatus("FAILED")
                    .attempts(1)
                    .providerResponse(String.valueOf(ex.getMessage()))
                    .timestamp(Instant.now())
                    .build());

            // Release the lock so a legitimate retry isn't blocked; leave the
            // message on the queue so SQS's redrive policy can route it to
            // the DLQ after maxReceiveCount is exceeded.
            redisTemplate.delete(lockKey);
        }
    }

    private void sendEmail(String recipient, String reservationId, String bodyJson) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(fromAddress);
        mail.setTo(recipient);
        mail.setSubject("Your order is confirmed - " + reservationId);
        mail.setText("Thanks for your order!\n\nReservation: " + reservationId
                + "\n\nDetails:\n" + bodyJson);
        mailSender.send(mail);
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
