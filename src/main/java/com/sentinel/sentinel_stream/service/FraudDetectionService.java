package com.sentinel.sentinel_stream.service;

import com.sentinel.sentinel_stream.dto.FraudAssessment;
import com.sentinel.sentinel_stream.entity.Order;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class FraudDetectionService {

    private final BedrockRuntimeClient bedrockClient;

    public FraudDetectionService(BedrockRuntimeClient bedrockClient) {
        this.bedrockClient = bedrockClient;
    }

    public FraudAssessment analyzeRisk(Order order) {
        // 1. Set Model ID to Amazon Nova Lite
        String modelId = "us.amazon.nova-2-lite-v1:0";

        // Append reinforced instructions to the prompt for strict compliance with the response format
        // 2. Utilize Nova 2 enhanced prompt logic
        String prompt = String.format(
                "You are an expert E-commerce Fraud Analyst. " +
                        "Task: Analyze the following order for fraud risk based on geopolitical factors, IP-location mismatch, and transaction amount. " +
                        "Data - Amount: %s %s, IP: %s, Destination: %s. " +
                        "Instructions: Think step-by-step to assess the risk. " +
                        "Output: Return ONLY a JSON with keys 'risk_score' (0.0-1.0) and a concise, professional 'reason' (under 100 words). " +
                        "Format: {\"risk_score\": 0.5, \"reason\": \"...\"}",
                order.getAmount(), order.getCurrency(), order.getIpAddress(), order.getShippingCountry()
        );

        // 2. Construct the payload in the expected Amazon Nova format (Messages API)
        JSONObject payload = new JSONObject()
                .put("messages", new org.json.JSONArray().put(
                        new JSONObject()
                                .put("role", "user")
                                .put("content", new org.json.JSONArray().put(
                                        new JSONObject().put("text", prompt)
                                ))
                ))
                // Nova-specific inference configuration
                .put("inferenceConfig", new JSONObject()
                        .put("max_new_tokens", 1000)
                        .put("temperature", 0.1)
                );

        try {
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .body(SdkBytes.fromString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            InvokeModelResponse response = bedrockClient.invokeModel(request);

            // 3. Parse the inference result from Amazon Nova
            JSONObject result = new JSONObject(response.body().asUtf8String());
            String content = result.getJSONObject("output")
                    .getJSONObject("message")
                    .getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text");

            // 💡 在 CloudWatch 看到 AI 到底回傳了什麼
            System.out.println("--- DEBUG START ---");
            System.out.println("Raw Content from AI: " + content);
            System.out.println("--- DEBUG END ---");

            // Robust Extraction: Capture only the content between the first '{' and the last '}'
            // to filter out potential conversational preamble from the AI.
            int firstBrace = content.indexOf("{");
            int lastBrace = content.lastIndexOf("}");

            // --- Error Handling: Manage AI response anomalies and Safety Filter blocking ---
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                // Success Case: Extract and parse valid JSON content
                String jsonStr = content.substring(firstBrace, lastBrace + 1);
                JSONObject aiJson = new JSONObject(jsonStr);

                double score = aiJson.getDouble("risk_score");
                String reason = aiJson.getString("reason");

                return new FraudAssessment(score, reason, (score > 0.75 ? "REJECTED" : "APPROVED"));
            } else {
                // Anomaly Case: AI response blocked by safety filters or invalid format
                return new FraudAssessment(0.99, "AI response blocked/invalid", "REJECTED");
            }

        } catch (Exception e) {
            // System Error Handling: Connection issues or AWS service failures
            return new FraudAssessment(0.5, "System error: " + e.getMessage(), "PENDING");
        }
    }
}
