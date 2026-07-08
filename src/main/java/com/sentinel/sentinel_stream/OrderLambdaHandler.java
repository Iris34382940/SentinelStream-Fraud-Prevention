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
public class OrderLambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Region CURRENT_REGION = Region.of(System.getenv("AWS_REGION"));
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    private static final Map<String, Map<String, String>> TRANSLATIONS = Map.of(
            "TraditionalChinese", Map.of(
                    "subject", "🚨 高風險詐欺警告！",
                    "body", "偵測到可疑交易！\n用戶: %s\n風險評分: %s\n原因: %s"
            ),
            "SimplifiedChinese", Map.of(
                    "subject", "🚨 高风险欺诈警告！",
                    "body", "检测到可疑交易！\n用户: %s\n风险评分: %s\n原因: %s"
            ),
            "Japanese", Map.of(
                    "subject", "🚨 高リスク詐欺警告！",
                    "body", "不正なアクティビティが検出されました！\nユーザー: %s\nリスクスコア: %s\n理由: %s"
            ),
            "Korean", Map.of(
                    "subject", "🚨 고위험 사기 경고!",
                    "body", "의심스러운 거래가 감지되었습니다!\n사용자: %s\n위험 점수: %s\n사유: %s"
            ),
            "French", Map.of(
                    "subject", "🚨 Alerte à la fraude à haut risque !",
                    "body", "Activité frauduleuse détectée !\nUtilisateur : %s\nScore de risque : %s\nRaison : %s"
            ),
            "English", Map.of(
                    "subject", "🚨 High Risk Fraud Alert!",
                    "body", "Fraudulent activity detected!\nUser: %s\nRisk Score: %s\nReason: %s"
            )
    );

    private static final DynamoDbClient dbClient = DynamoDbClient.builder()
            .region(CURRENT_REGION)
            .build();
    private static final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
            .region(CURRENT_REGION)
            .build();
    private static final FraudDetectionService fraudService = new FraudDetectionService(bedrockClient);
    private static final SnsClient snsClient = SnsClient.builder()
            .region(CURRENT_REGION)
            .build();
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        // 取得 Lambda 專用的記錄器
        com.amazonaws.services.lambda.runtime.LambdaLogger logger = context.getLogger();
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        try {
            logger.log("Received order request: " + request.getBody());
            // 1. Deserialize the incoming JSON order from API Gateway
            Order order = objectMapper.readValue(request.getBody(), Order.class);
            // --- Assign a unique UUID to the order object ---
            order.setId(UUID.randomUUID().toString());
            order.setCreatedAt(java.time.Instant.now().toString());
            // 2. Invoke Amazon Nova AI for fraud risk inference
            String language = System.getenv().getOrDefault("TARGET_LANGUAGE", "English");
            FraudAssessment assessment = fraudService.analyzeRisk(order, language);
            // 直接從物件拿資料，IDE 會幫忙檢查拼字
            double score = assessment.getRiskScore();
            String reason = assessment.getReason();
            String status = assessment.getStatus();
            // --- Enrich the order object with AI analysis results ---
            order.setRiskScore(score);
            order.setRiskReason(reason);
            order.setStatus(status);
            logger.log("AI Assessment - OrderID: " + order.getId() + ", Status: " + status + ", Score: " + score);
            // 3. Persist transaction data into Amazon DynamoDB
            String tableName = System.getenv("TABLE_NAME");
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("orderId", AttributeValue.builder().s(order.getId()).build());
            item.put("userId", AttributeValue.builder().s(order.getUserId()).build());
            item.put("riskScore", AttributeValue.builder().n(String.valueOf(order.getRiskScore())).build());
            item.put("reason", AttributeValue.builder().s(order.getRiskReason()).build());
            item.put("createdAt", AttributeValue.builder().s(order.getCreatedAt()).build());
            item.put("status", AttributeValue.builder().s(order.getStatus()).build());
            dbClient.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
            if ("REJECTED".equals(status)) {
                String snsTopicArn = System.getenv("SNS_TOPIC_ARN");
                Map<String, String> content = TRANSLATIONS.getOrDefault(language, TRANSLATIONS.get("English"));

                String localizedSubject = content.get("subject");
                String localizedMessage = String.format(content.get("body"), order.getUserId(), score, reason);

                logger.log("High risk detected. Sending SNS alert in [" + language + "] to: " + snsTopicArn);

                snsClient.publish(PublishRequest.builder()
                        .topicArn(snsTopicArn)
                        .subject(localizedSubject)
                        .message(localizedMessage)
                        .build());
            }
            // --- Serialize the enriched order object back to JSON for the response ---
            String fullResponseBody = objectMapper.writeValueAsString(order);
            return response
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(fullResponseBody);
        } catch (Exception e) {
            logger.log("CRITICAL ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Internal Service Error processing order", e);
        }
    }
}
