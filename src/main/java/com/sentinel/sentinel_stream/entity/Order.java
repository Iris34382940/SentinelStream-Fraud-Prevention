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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;
    private BigDecimal amount;    // 訂單金額
    private String currency;      // 幣別 (如 USD, TWD)
    private String ipAddress;     // 關鍵：用來追蹤地理位置
    private String shippingCountry; // 收貨國家
    private String status;        // PENDING, APPROVED, REJECTED
    private Double riskScore;     // AI 計算出的風險評分 (0-1)
    private String riskReason;    // 讓AI紀錄原因

    private LocalDateTime createdAt = LocalDateTime.now();
}
