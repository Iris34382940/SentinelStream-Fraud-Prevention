package com.sentinel.sentinel_stream.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "orders")
public class Order {
    @Id
    private String id; // Unique transaction identifier (UUID)

    private String userId;
    private BigDecimal amount;    // Total order amount
    private String currency;      // Currency code (e.g., USD, TWD)
    private String ipAddress;     // Crucial: Used for geolocation and anomaly tracking
    private String shippingCountry; // Destination country for risk assessment
    private String status;        // Transaction status: PENDING, APPROVED, REJECTED
    private Double riskScore;     // AI-calculated risk score (Range: 0.0 - 1.0)
    private String riskReason;    // Detailed reasoning provided by AI inference

    private LocalDateTime createdAt = LocalDateTime.now(); // Timestamp for auditing and record-keeping
}
