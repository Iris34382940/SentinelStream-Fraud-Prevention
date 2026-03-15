package com.sentinel.sentinel_stream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.sentinel_stream.entity.Order;
import com.sentinel.sentinel_stream.service.FraudDetectionService;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OrderLambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FraudDetectionService fraudService = new FraudDetectionService();

    // 1. 初始化 DynamoDB Client (放在這裡可以共用連線，加快速度)
    private final DynamoDbClient dbClient = DynamoDbClient.builder()
            .region(Region.US_EAST_1)
            .build();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            // 1. 解析 API Gateway 傳來的 JSON 訂單
            Order order = objectMapper.readValue(request.getBody(), Order.class);

            // 2. 呼叫 AI (Bedrock) 進行分析
            JSONObject aiResult = fraudService.analyzeRisk(order);

            // 從 AI 結果中提取分數與原因
            double score = aiResult.getDouble("risk_score");
            String reason = aiResult.getString("reason");

            // 3. 將結果寫入 DynamoDB
            String tableName = System.getenv("TABLE_NAME");

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("orderId", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
            item.put("userId", AttributeValue.builder().s(order.getUserId()).build());
            item.put("riskScore", AttributeValue.builder().n(String.valueOf(score)).build());
            item.put("reason", AttributeValue.builder().s(reason).build()); // 這裡變數名要對齊
            item.put("status", AttributeValue.builder().s(score > 0.8 ? "REJECTED" : "APPROVED").build());

            dbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());

            // 4. 回傳結果
            return response
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(aiResult.toString());

        } catch (Exception e) {
            return response.withStatusCode(500).withBody("Error: " + e.getMessage());
        }
    }
}
