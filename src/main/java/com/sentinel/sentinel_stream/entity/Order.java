package com.sentinel.sentinel_stream.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private String id; // Unique transaction identifier (UUID)
    private String userId;
    private BigDecimal amount;    // Total order amount
    private String currency;      // Currency code (e.g., USD, TWD)
    private String ipAddress;     // Crucial: Used for geolocation and anomaly tracking
    private String shippingCountry; // Destination country for risk assessment
    private String status;        // Transaction status: PENDING, APPROVED, REJECTED
    private Double riskScore;     // AI-calculated risk score (Range: 0.0 - 1.0)
    private String riskReason;    // Detailed reasoning provided by AI inference

    private String createdAt;
}
