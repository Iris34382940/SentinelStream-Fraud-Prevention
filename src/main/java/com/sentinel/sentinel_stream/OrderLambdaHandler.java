package com.sentinel.sentinel_stream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.sentinel_stream.entity.Order;
import com.sentinel.sentinel_stream.service.FraudDetectionService;
import org.json.JSONObject;

import java.util.Map;

public class OrderLambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FraudDetectionService fraudService = new FraudDetectionService();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            // 1. 解析 API Gateway 傳來的 JSON 訂單
            Order order = objectMapper.readValue(request.getBody(), Order.class);

            // 2. 呼叫你寫好的 AI 服務 (Bedrock)
            JSONObject aiResult = fraudService.analyzeRisk(order);

            // 3. 封裝結果
            String responseBody = aiResult.toString();

            return response
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(responseBody);

        } catch (Exception e) {
            return response.withStatusCode(500).withBody("Error: " + e.getMessage());
        }
    }
}
