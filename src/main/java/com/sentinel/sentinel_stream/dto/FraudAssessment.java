package com.sentinel.sentinel_stream.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudAssessment {
    private double riskScore;
    private String reason;
    private String analysis;  // 存放 AI 的詳細分析過程
    private String status; // 例如: APPROVED, REJECTED
}
