package com.sentinel.sentinel_stream.service;
import com.sentinel.sentinel_stream.dto.FraudAssessment;
import com.sentinel.sentinel_stream.entity.Order;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import org.json.JSONObject;
import org.json.JSONException;
import java.nio.charset.StandardCharsets;
public class FraudDetectionService {
    private final BedrockRuntimeClient bedrockClient;
    private final String modelId = System.getenv().getOrDefault("MODEL_ID", "us.amazon.nova-2-lite-v1:0");
    public FraudDetectionService(BedrockRuntimeClient bedrockClient) {
        this.bedrockClient = bedrockClient;
    }
    public String invokeNova(String prompt, double temperature) {
        JSONObject payload = new JSONObject()
                .put("messages", new org.json.JSONArray().put(
                        new JSONObject()
                                .put("role", "user")
                                .put("content", new org.json.JSONArray().put(
                                        new JSONObject().put("text", prompt)
                                ))
                ))
                .put("inferenceConfig", new JSONObject()
                        .put("max_new_tokens", 1000)
                        .put("temperature", temperature)
                );
        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .body(SdkBytes.fromString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        InvokeModelResponse response = bedrockClient.invokeModel(request);
        JSONObject result = new JSONObject(response.body().asUtf8String());
        return result.getJSONObject("output")
                .getJSONObject("message")
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text");
    }
    public FraudAssessment analyzeRisk(Order order, String language) {
        String prompt = """
        You are a Senior E-commerce Fraud Investigator.
        Task: Perform a deep-dive fraud risk assessment on the following transaction.
        Data to Analyze:
        - Transaction Amount: %s %s
        - Source IP Address: %s
        - Shipping Destination: %s
        - User Time Zone: %s
        Instructions:
        1. Evaluate the geopolitical risk, IP-to-Destination consistency, and Time Zone alignment.
        2. Assess if the transaction amount is atypical for this route or time.
        3. Formulate your reasoning step-by-step.
        4. 💡 IMPORTANT: You must write the 'analysis' and 'reason' in %s.
        Output Requirement: Return ONLY a JSON object with the following keys:
        - 'analysis': A detailed step-by-step breakdown of your reasoning (max 150 words).
        - 'risk_score': A float between 0.0 and 1.0 (Higher = Higher Risk).
        - 'reason': A short, professional summary for the customer support dashboard (max 50 words).
        Format: {"analysis": "...", "risk_score": 0.5, "reason": "..."}
        """.formatted(
                order.getAmount(),
                order.getCurrency(),
                order.getIpAddress(),
                order.getShippingCountry(),
                order.getZoneId(),
                language
        );
        try {
            String content = invokeNova(prompt, 0.1);
            System.out.println("--- DEBUG START ---");
            System.out.println("Raw Content from AI: " + content);
            System.out.println("--- DEBUG END ---");
            if (content == null || content.isBlank()) {
                return getSafetyNetAssessment("AI returned empty content");
            }
            int firstBrace = content.indexOf("{");
            int lastBrace = content.lastIndexOf("}");
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                try {
                    String jsonStr = content.substring(firstBrace, lastBrace + 1);
                    JSONObject aiJson = new JSONObject(jsonStr);
                    double score = aiJson.optDouble("risk_score", 0.5);
                    String reason = aiJson.optString("reason", "AI provided incomplete summary");
                    String analysis = aiJson.optString("analysis", "No detailed analysis available.");
                    return new FraudAssessment(score, reason, analysis, (score > 0.75 ? "REJECTED" : "APPROVED"));
                } catch (JSONException je) {
                    return getSafetyNetAssessment("Malformed JSON from AI");
                }
            } else {
                return getSafetyNetAssessment("No JSON block found in AI response");
            }
        } catch (Exception e) {
            // 系統級錯誤 (如 Bedrock Timeout) 的處理
            e.printStackTrace();
            // 在接案情境中，建議給予 0.5 中性分數並標記為 PENDING 或 MANUAL_REVIEW
            return new FraudAssessment(0.5, "System stability issue: " + e.getMessage(), "System Exception occurred during AI inference.", "MANUAL_REVIEW");
        }
    }
    private FraudAssessment getSafetyNetAssessment(String internalNote) {
        return new FraudAssessment(0.5, "AI Analysis Error: " + internalNote,
                "The system could not parse AI response. Please check manually.", "APPROVED_WITH_CAUTION");
    }
}
