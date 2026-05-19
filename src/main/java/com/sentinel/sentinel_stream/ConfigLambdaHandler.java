package com.sentinel.sentinel_stream;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;
public class ConfigLambdaHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dbClient = DynamoDbClient.create();
    private final SchedulerClient schedulerClient = SchedulerClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String scheduleName = System.getenv("SCHEDULE_NAME");
    private final String tableName = System.getenv("TABLE_NAME");
    private final String trendLambdaArn = System.getenv("TREND_LAMBDA_ARN");
    private final String schedulerRoleArn = System.getenv("SCHEDULER_ROLE_ARN");
    private final String defaultTimeZone = System.getenv().getOrDefault("TARGET_TIMEZONE", "Asia/Taipei");
    private static final String CONFIG_PK = "SYSTEM_REPORT_STATE";
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            Map<String, Object> body = objectMapper.readValue(request.getBody(), Map.class);
            normalizeBody(body);
            saveConfigToDb(body);
            ZonedDateTime firstRun = calculateFirstRun(body);
            scheduleTask(firstRun, (String) body.get("targetTimeZone"));
            return new APIGatewayProxyResponseEvent().withStatusCode(200)
                    .withBody("Config saved. First report scheduled at: " + firstRun.toString());
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return new APIGatewayProxyResponseEvent().withStatusCode(500).withBody("Configuration Error: " + e.getMessage());
        }
    }
    private void normalizeBody(Map<String, Object> body) {
        String[] upperFields = {"strategy", "periodType", "dayOfWeek"};
        for (String field : upperFields) {
            if (body.containsKey(field) && body.get(field) != null) {
                body.put(field, String.valueOf(body.get(field)).trim().toUpperCase());
            }
        }
        body.putIfAbsent("targetTimeZone", defaultTimeZone);
        body.putIfAbsent("sendTime", "09:00");
    }
    private void saveConfigToDb(Map<String, Object> body) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId", AttributeValue.builder().s(CONFIG_PK).build());
        body.forEach((k, v) -> {
            if (v == null) return;
            boolean isNumericField = k.equals("intervalDays") || k.equals("dayOfMonth") || k.equals("monthOfYear");
            if (v instanceof Number || isNumericField) {
                try {
                    item.put(k, AttributeValue.builder().n(String.valueOf(v)).build());
                } catch (Exception e) {
                    item.put(k, AttributeValue.builder().s(String.valueOf(v)).build());
                }
            } else {
                item.put(k, AttributeValue.builder().s(String.valueOf(v)).build());
            }
        });
        dbClient.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }
    private ZonedDateTime calculateFirstRun(Map<String, Object> body) {
        String strategy = (String) body.get("strategy");
        ZoneId zone = ZoneId.of((String) body.get("targetTimeZone"));
        LocalTime sendTime = LocalTime.parse((String) body.get("sendTime"));
        ZonedDateTime now = ZonedDateTime.now(zone).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        ZonedDateTime firstRun;
        switch (strategy) {
            case "INTERVAL":
                LocalDate start = LocalDate.parse((String) body.get("startDate"));
                int interval = Integer.parseInt(String.valueOf(body.get("intervalDays")));
                ZonedDateTime firstTheoreticalPoint = start.plusDays(interval).atTime(sendTime).atZone(zone);
                if (firstTheoreticalPoint.isBefore(now)) {
                    firstRun = now.plusMinutes(1);
                } else {
                    firstRun = firstTheoreticalPoint;
                }
                break;
            case "PERIODIC":
                String pType = (String) body.get("periodType");
                if ("WEEK".equals(pType)) {
                    firstRun = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
                } else if ("MONTH".equals(pType)) {
                    firstRun = (now.getDayOfMonth() == 1) ? now : now.with(TemporalAdjusters.firstDayOfNextMonth());
                } else {
                    firstRun = (now.getDayOfYear() == 1) ? now : now.with(TemporalAdjusters.firstDayOfNextYear());
                }
                firstRun = firstRun.with(sendTime);
                if (!firstRun.isAfter(now)) {
                    if ("WEEK".equals(pType)) firstRun = firstRun.plusWeeks(1);
                    else if ("MONTH".equals(pType)) firstRun = firstRun.plusMonths(1);
                    else firstRun = firstRun.plusYears(1);
                }
                break;
            case "SPECIFIC":
                if (body.containsKey("dayOfWeek")) {
                    DayOfWeek targetW = DayOfWeek.valueOf((String) body.get("dayOfWeek"));
                    firstRun = now.with(TemporalAdjusters.previousOrSame(targetW)).with(sendTime);
                    if (!firstRun.isAfter(now)) {
                        firstRun = firstRun.plusWeeks(1);
                    }
                } else if (body.containsKey("dayOfMonth")) {
                    int targetD = Integer.parseInt(String.valueOf(body.get("dayOfMonth")));
                    firstRun = now.withDayOfMonth(Math.min(targetD, now.toLocalDate().lengthOfMonth())).with(sendTime);

                    if (!firstRun.isAfter(now)) {
                        ZonedDateTime nextMonth = firstRun.plusMonths(1);
                        firstRun = nextMonth.withDayOfMonth(Math.min(targetD, nextMonth.toLocalDate().lengthOfMonth()));
                    }
                } else {
                    int month = Integer.parseInt(String.valueOf(body.get("monthOfYear")));
                    firstRun = now.withMonth(month).withDayOfMonth(1).with(sendTime);

                    if (!firstRun.isAfter(now)) {
                        firstRun = firstRun.plusYears(1);
                    }
                }
                break;
            default:
                firstRun = now.plusDays(1).with(sendTime);
        }
        return firstRun.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }
    private void scheduleTask(ZonedDateTime nextRun, String zoneId) {
        if (trendLambdaArn == null || trendLambdaArn.isEmpty()) {
            throw new RuntimeException("CRITICAL ERROR: Environment variable TREND_LAMBDA_ARN is missing!");
        }
        String atExpression = "at(" + nextRun.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + ")";
        String fullLambdaArn = trendLambdaArn.endsWith(":live") ? trendLambdaArn : trendLambdaArn + ":live";
        try {
            try {
                schedulerClient.deleteSchedule(DeleteScheduleRequest.builder().name(this.scheduleName).build());
            } catch (Exception ignore) {}
            schedulerClient.createSchedule(CreateScheduleRequest.builder()
                    .name(scheduleName)
                    .scheduleExpression(atExpression)
                    .scheduleExpressionTimezone(zoneId)
                    .target(Target.builder()
                            .arn(fullLambdaArn)
                            .roleArn(schedulerRoleArn)
                            .input("{}")
                            .build())
                    .actionAfterCompletion(ActionAfterCompletion.DELETE)
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to schedule task: " + e.getMessage());
        }
    }
}
