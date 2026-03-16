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
            .region(Region.US_EAST_1)
            .build();

    public JSONObject analyzeRisk(Order order) {
        // 1. Set Model ID to Amazon Nova Lite
        String modelId = "us.amazon.nova-2-lite-v1:0";

        // Append reinforced instructions to the prompt for strict compliance with the response format
        // 2. Utilize Nova 2 enhanced prompt logic
        String prompt = String.format(
                "You are an expert E-commerce Fraud Analyst. " +
                        "Task: Analyze the following order for fraud risk based on geopolitical factors, IP-location mismatch, and transaction amount. " +
                        "Data - Amount: %s %s, IP: %s, Destination: %s. " +
                        "Instructions: Think step-by-step to assess the risk. " +
                        "Output: Return ONLY a JSON with keys 'risk_score' (decimal 0.0-1.0) and 'reason' (clear explanation).",
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
                        .put("max_new_tokens", 200)
                        .put("temperature", 0.1)
                );

        try {
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .body(SdkBytes.fromString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            InvokeModelResponse response = bedrockClient.invokeModel(request);
            JSONObject result = new JSONObject(response.body().asUtf8String());

            // 3. Parse the inference result from Amazon Nova
            String content = result.getJSONObject("output")
                    .getJSONObject("message")
                    .getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text");

            // Robust Extraction: Capture only the content between the first '{' and the last '}'
            // to filter out potential conversational preamble from the AI.
            int firstBrace = content.indexOf("{");
            int lastBrace = content.lastIndexOf("}");

            // --- Error Handling: Manage AI response anomalies and Safety Filter blocking ---
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                // Success Case: Extract and parse valid JSON content
                content = content.substring(firstBrace, lastBrace + 1);
                return new JSONObject(content);
            } else {
                // Anomaly Case: AI response blocked by safety filters or invalid format
                System.err.println("AI Response was blocked or invalid: " + content);

                return new JSONObject()
                        .put("risk_score", 0.99)
                        .put("reason", "Inference blocked by safety filters - potential high-risk anomaly.");
            }

        } catch (Exception e) {
            // System Error Handling: Connection issues or AWS service failures
            System.err.println("Nova AI Invocation Failed: " + e.getMessage());
            return new JSONObject()
                    .put("risk_score", 0.5)
                    .put("reason", "AI analysis temporarily unavailable due to system error. Using neutral fallback score.");
        }
    }
}
