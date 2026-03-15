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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Lambda Handler for processing e-commerce order fraud detection.
 */
public class OrderLambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Configure Jackson ObjectMapper with Java 21 Time support
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private final FraudDetectionService fraudService = new FraudDetectionService();

    // 1. Initialize AWS SDK Clients (DynamoDB & SNS)
    private final DynamoDbClient dbClient = DynamoDbClient.builder()
            .region(Region.US_EAST_1)
            .build();

    private final SnsClient snsClient = SnsClient.builder()
            .region(Region.US_EAST_1)
            .build();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            // 1. Deserialize the incoming JSON order from API Gateway
            Order order = objectMapper.readValue(request.getBody(), Order.class);

            // --- Assign a unique UUID to the order object ---
            order.setId(UUID.randomUUID().toString());

            // 2. Invoke Amazon Nova AI for fraud risk inference
            JSONObject aiResult = fraudService.analyzeRisk(order);
            double score = aiResult.getDouble("risk_score");
            String reason = aiResult.getString("reason");

            // --- Enrich the order object with AI analysis results ---
            order.setRiskScore(score);
            order.setRiskReason(reason);
            order.setStatus(score > 0.8 ? "REJECTED" : "APPROVED");

            // 3. Persist transaction data into Amazon DynamoDB
            String tableName = System.getenv("TABLE_NAME");
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("orderId", AttributeValue.builder().s(order.getId()).build());
            item.put("userId", AttributeValue.builder().s(order.getUserId()).build());
            item.put("riskScore", AttributeValue.builder().n(String.valueOf(score)).build());
            item.put("reason", AttributeValue.builder().s(reason).build());
            item.put("status", AttributeValue.builder().s(order.getStatus()).build());

            dbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());

            // --- 4. High-Risk Alert: Trigger automated Email notification via Amazon SNS ---
            if (score > 0.8) {
                String snsTopicArn = System.getenv("SNS_TOPIC_ARN");
                snsClient.publish(PublishRequest.builder()
                        .topicArn(snsTopicArn)
                        .subject("🚨 High Risk Fraud Alert!")
                        .message("Fraudulent activity detected!\nUser: " + order.getUserId() +
                                "\nRisk Score: " + score +
                                "\nReason: " + reason)
                        .build());
            }

            // --- Serialize the enriched order object back to JSON for the response ---
            String fullResponseBody = objectMapper.writeValueAsString(order);

            return response
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(fullResponseBody);

        } catch (Exception e) {
            // Error handling for system failures
            return response.withStatusCode(500).withBody("System Error: " + e.getMessage());
        }
    }
}
