package com.sentinel.sentinel_stream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sentinel.sentinel_stream.entity.Order;
import com.sentinel.sentinel_stream.service.FraudDetectionService;
import com.sentinel.sentinel_stream.dto.FraudAssessment;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Lambda Handler for processing e-commerce order fraud detection.
 */
public class OrderLambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Configure Jackson ObjectMapper with Java 21 Time support
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    // 1. Initialize AWS SDK Clients (DynamoDB & SNS)
    private static final DynamoDbClient dbClient = DynamoDbClient.builder()
            .region(Region.US_EAST_1)
            .build();

    private static final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
            .region(Region.US_EAST_1).build();

    private static final FraudDetectionService fraudService = new FraudDetectionService(bedrockClient);

    private static final SnsClient snsClient = SnsClient.builder()
            .region(Region.US_EAST_1)
            .build();


    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        // 取得 Lambda 專用的記錄器
        com.amazonaws.services.lambda.runtime.LambdaLogger logger = context.getLogger();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        try {
            // Log 1: 記錄接收到的原始請求
            logger.log("Received order request: " + request.getBody());

            // 1. Deserialize the incoming JSON order from API Gateway
            Order order = objectMapper.readValue(request.getBody(), Order.class);

            // --- Assign a unique UUID to the order object ---
            order.setId(UUID.randomUUID().toString());
            order.setCreatedAt(java.time.Instant.now().toString());

            // 2. Invoke Amazon Nova AI for fraud risk inference
            FraudAssessment assessment = fraudService.analyzeRisk(order);

            // 直接從物件拿資料，IDE 會幫忙檢查拼字
            double score = assessment.getRiskScore();
            String reason = assessment.getReason();
            String status = assessment.getStatus();

            // --- Enrich the order object with AI analysis results ---
            order.setRiskScore(score);
            order.setRiskReason(reason);
            order.setStatus(status);

            // Log 2: 記錄 AI 判定的分數與狀態
            logger.log("AI Assessment - OrderID: " + order.getId() + ", Status: " + status + ", Score: " + score);

            // 3. Persist transaction data into Amazon DynamoDB
            String tableName = System.getenv("TABLE_NAME");
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("orderId", AttributeValue.builder().s(order.getId()).build());
            item.put("userId", AttributeValue.builder().s(order.getUserId()).build());
            item.put("riskScore", AttributeValue.builder().n(String.valueOf(score)).build());
            item.put("reason", AttributeValue.builder().s(reason).build());
            item.put("createdAt", AttributeValue.builder().s(order.getCreatedAt()).build());
            item.put("status", AttributeValue.builder().s(order.getStatus()).build());

            dbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());

            // --- 4. High-Risk Alert: Trigger automated Email notification via Amazon SNS ---
            if ("REJECTED".equals(status)) {
                String snsTopicArn = System.getenv("SNS_TOPIC_ARN");

                // Log 3: 記錄發送警告
                logger.log("High risk detected. Sending SNS alert to: " + snsTopicArn);

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
            // Log 4: 記錄嚴重錯誤訊息
            logger.log("CRITICAL ERROR: Failed to process order. Message: " + e.getMessage());

            e.printStackTrace();


            // Error handling for system failures
            return response.withStatusCode(500).withBody("System Error: " + e.getMessage());
        }
    }
}
