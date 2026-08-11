package com.omnialert.reserve.repository;

import com.omnialert.reserve.document.NotificationTelemetry;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationTelemetryRepository extends MongoRepository<NotificationTelemetry, String> {
}
