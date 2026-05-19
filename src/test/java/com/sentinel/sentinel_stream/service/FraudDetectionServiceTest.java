package com.sentinel.sentinel_stream.service;

import com.sentinel.sentinel_stream.dto.FraudAssessment;
import com.sentinel.sentinel_stream.entity.Order;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class FraudDetectionServiceTest {

    @Test
    void testAnalyzeRisk_Success() {
        // 1. 模擬 (Mock) Bedrock Client
        BedrockRuntimeClient mockBedrock = Mockito.mock(BedrockRuntimeClient.class);

        // 2. 構建正確的 JSON 結構 (關鍵修正：補齊 content 陣列與內層 JSON)
        String innerAiJson = "{\"analysis\": \"Normal pattern detected.\", \"risk_score\": 0.1, \"reason\": \"Safe source.\"}";

        // 這是 AWS Bedrock 要求的完整回傳格式：output -> message -> content ->
        String mockJsonResponse = """
{
"output": {
"message": {
"content": [
{
"text": "{\\"analysis\\": \\"Normal pattern detected.\\", \\"risk_score\\": 0.1, \\"reason\\": \\"Safe source.\\"}"
}
]
}
}
}
""";
        InvokeModelResponse mockResponse = InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(mockJsonResponse))
                .build();

        when(mockBedrock.invokeModel(any(InvokeModelRequest.class))).thenReturn(mockResponse);

        Order testOrder = Order.builder()
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .ipAddress("1.1.1.1")
                .shippingCountry("JP")
                .zoneId("Asia/Tokyo")
                .build();

        // 執行測試
        FraudDetectionService service = new FraudDetectionService(mockBedrock);

        FraudAssessment result = service.analyzeRisk(testOrder, "English");

        // Assertions
        assertNotNull(result, "Result should not be null");
        assertEquals(0.1, result.getRiskScore(), 0.001, "Risk score should be 0.1");
        assertEquals("APPROVED", result.getStatus(), "Status should be APPROVED");
        assertEquals("Normal pattern detected.", result.getAnalysis(), "Analysis content mismatch");
    }
}
