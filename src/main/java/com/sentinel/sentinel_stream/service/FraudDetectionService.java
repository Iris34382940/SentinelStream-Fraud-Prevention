package com.sentinel.sentinel_stream.service;

import com.sentinel.sentinel_stream.entity.Order;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.core.SdkBytes;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

@Service
public class FraudDetectionService {

    private final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
            .region(Region.US_EAST_1) // 與你設定的 CLI Region 一致
            .build();

    public JSONObject analyzeRisk(Order order) {
        // 建立給 AI 的指令 (Prompt)
        String prompt = String.format(
                "Analyze this e-commerce order for fraud risk. " +
                "Amount: %s %s, IP: %s, Destination: %s. " +
                "Return ONLY a JSON with keys 'risk_score' (0.0 to 1.0) and 'reason' (brief string).",
                order.getAmount(), order.getCurrency(), order.getIpAddress(), order.getShippingCountry()
        );

        // 封裝成 Claude 模型需要的格式
        JSONObject payload = new JSONObject()
                .put("anthropic_version", "bedrock-2023-05-31")
                .put("max_tokens", 100)
                .put("messages", new org.json.JSONArray().put(
                        new JSONObject().put("role", "user").put("content", prompt)
                ));

        try {
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId("us.anthropic.claude-3-5-haiku-20241022-v1:0") // 2026 年主流的 3.5 版
                    .contentType("application/json")
                    .body(SdkBytes.fromString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            InvokeModelResponse response = bedrockClient.invokeModel(request);
            JSONObject result = new JSONObject(response.body().asUtf8String());

            // 解析 AI 回傳的分數
            String content = result.getJSONArray("content").getJSONObject(0).getString("text");
            return new JSONObject(content); // 直接回傳 {"risk_score": 0.8, "reason": "..."}

        } catch (Exception e) {
            System.err.println("AI 呼叫失敗: " + e.getMessage());
            // 失敗時，手動建立一個中庸的分數與錯誤理由
            return new JSONObject()
                    .put("risk_score", 0.5)
                    .put("reason", "AI 呼叫失敗，採取預設值");

        }
    }
}

