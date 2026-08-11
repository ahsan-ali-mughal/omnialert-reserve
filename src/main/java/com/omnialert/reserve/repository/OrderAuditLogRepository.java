package com.omnialert.reserve.repository;

import com.omnialert.reserve.document.OrderAuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderAuditLogRepository extends MongoRepository<OrderAuditLog, String> {
}
