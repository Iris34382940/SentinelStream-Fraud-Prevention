package com.sentinel.sentinel_stream;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;
import com.sentinel.sentinel_stream.service.FraudDetectionService;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.stream.Collectors;
public class TrendLambdaHandler implements RequestHandler<Map<String, Object>, String> {
    private final DynamoDbClient dbClient;
    private final SesClient sesClient;
    private final BedrockRuntimeClient bedrockClient;
    private final SchedulerClient schedulerClient;
    private final String scheduleName = System.getenv("SCHEDULE_NAME");
    private final String tableName = System.getenv("TABLE_NAME");
    private final String sourceEmail = System.getenv("SOURCE_EMAIL");
    private final String recipientEmail = System.getenv("RECIPIENT_EMAIL");
    private final String schedulerRoleArn = System.getenv("SCHEDULER_ROLE_ARN");
    private final FraudDetectionService fraudService;
    private final String defaultTimeZone = System.getenv().getOrDefault("TARGET_TIMEZONE", "Asia/Taipei");
    private final String defaultLanguage = System.getenv().getOrDefault("TARGET_LANGUAGE", "English");
    private static final String CONFIG_PK = "SYSTEM_REPORT_STATE";
    public TrendLambdaHandler() {
        Region region = Region.of(System.getenv("AWS_REGION"));
        this.dbClient = DynamoDbClient.builder().region(region).build();
        this.sesClient = SesClient.builder().region(region).build();
        this.bedrockClient = BedrockRuntimeClient.builder().region(region).build();
        this.schedulerClient = SchedulerClient.builder().region(region).build();
        this.fraudService = new FraudDetectionService(bedrockClient);
    }
    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        System.out.println("--- Lambda 開始執行 --- 策略為: " + event.toString());
        var logger = context.getLogger();
        try {
            GetItemResponse stateRes = dbClient.getItem(GetItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("orderId", AttributeValue.builder().s(CONFIG_PK).build()))
                    .build());
            if (!stateRes.hasItem()) return "SKIP: No config found.";
            Map<String, AttributeValue> config = new java.util.HashMap<>(stateRes.item());
            String targetZoneId = config.getOrDefault("targetTimeZone", AttributeValue.fromS(defaultTimeZone)).s();
            String strategy = config.getOrDefault("strategy", AttributeValue.fromS("PERIODIC")).s().trim().toUpperCase();
            ZoneId userZone = ZoneId.of(targetZoneId);
            ZonedDateTime now = ZonedDateTime.now(userZone);
            LocalTime sendTime = LocalTime.parse(config.getOrDefault("sendTime", AttributeValue.fromS("09:00")).s());
            LocalTime nowTimeClean = now.toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            LocalDate reportStartDate = calculateDataStartDate(config, now.toLocalDate(), nowTimeClean, sendTime);
            LocalDate reportEndDate;
            if (strategy.equals("INTERVAL")) {
                int intervalDays = Integer.parseInt(config.getOrDefault("intervalDays", AttributeValue.fromN("1")).n());
                reportEndDate = reportStartDate.plusDays(intervalDays);
            } else if (strategy.equals("PERIODIC")) {
                String pType = config.getOrDefault("periodType", AttributeValue.fromS("WEEK")).s().trim().toUpperCase();
                if ("WEEK".equals(pType)) reportEndDate = reportStartDate.plusWeeks(1);
                else if ("MONTH".equals(pType)) reportEndDate = reportStartDate.plusMonths(1);
                else reportEndDate = reportStartDate.plusYears(1);
            } else if (strategy.equals("SPECIFIC")) {
                if (config.containsKey("dayOfWeek")) reportEndDate = reportStartDate.plusWeeks(1);
                else if (config.containsKey("dayOfMonth")) reportEndDate = reportStartDate.plusMonths(1);
                else reportEndDate = reportStartDate.plusYears(1);
            } else {
                reportEndDate = now.toLocalDate();
            }
            boolean shouldSendEmail = false;
            if (strategy.equals("INTERVAL")) {
                ZonedDateTime theoreticalSendDateTime = reportEndDate.atTime(sendTime).atZone(userZone);
                shouldSendEmail = !theoreticalSendDateTime.isAfter(now);
                if (!shouldSendEmail) logger.log("閘門關閉：區間滿了但發送時間 " + sendTime + " 還沒到。");
            } else if (strategy.equals("PERIODIC") || strategy.equals("SPECIFIC")) {
                boolean isTargetDay = false;
                if (strategy.equals("PERIODIC")) {
                    String pType = config.getOrDefault("periodType", AttributeValue.fromS("WEEK")).s().trim().toUpperCase();
                    if ("WEEK".equals(pType)) isTargetDay = (now.getDayOfWeek() == DayOfWeek.MONDAY);
                    else if ("MONTH".equals(pType)) isTargetDay = (now.getDayOfMonth() == 1);
                    else if ("YEAR".equals(pType)) isTargetDay = (now.getDayOfYear() == 1);
                } else {
                    if (config.containsKey("dayOfWeek")) {
                        DayOfWeek targetDay = DayOfWeek.valueOf(config.get("dayOfWeek").s().trim().toUpperCase());
                        isTargetDay = (now.getDayOfWeek() == targetDay);
                    } else if (config.containsKey("dayOfMonth")) {
                        int d = Integer.parseInt(config.get("dayOfMonth").n());
                        isTargetDay = (now.getDayOfMonth() == Math.min(d, now.toLocalDate().lengthOfMonth()));
                    } else if (config.containsKey("monthOfYear")) {
                        int m = Integer.parseInt(config.get("monthOfYear").n());
                        isTargetDay = (now.getMonthValue() == m && now.getDayOfMonth() == 1);
                    }
                }
                shouldSendEmail = isTargetDay && !nowTimeClean.isBefore(sendTime);
                if (!shouldSendEmail) logger.log("閘門關閉：非目標日或發送時間 " + sendTime + " 還沒到。");
            } else {
                shouldSendEmail = true;
            }
            ZonedDateTime nextRun = calculateNextRun(config, now, sendTime);
            scheduleNext(context.getInvokedFunctionArn(), nextRun, targetZoneId);
            Map<String, AttributeValue> updatedItem = new java.util.HashMap<>(config);
            if ("INTERVAL".equals(strategy) && shouldSendEmail) {
                updatedItem.put("startDate", AttributeValue.builder().s(reportEndDate.toString()).build());
            }
            updatedItem.put("lastReportAt", AttributeValue.builder().s(now.toInstant().toString()).build());
            updatedItem.put("nextScheduledAt", AttributeValue.builder().s(nextRun.toInstant().toString()).build());
            dbClient.putItem(PutItemRequest.builder().tableName(tableName).item(updatedItem).build());
            if (!shouldSendEmail) {
                return "SUCCESS: 週期未滿不發信。已預約下次正式執行：" + nextRun;
            }
            LocalDate displayEndDate = reportEndDate.minusDays(1);
            String reportDateRange = reportStartDate + " ~ " + displayEndDate;
            String sinceDateStr = reportStartDate.atStartOfDay(userZone).toInstant().toString();
            String untilDateStr = reportEndDate.atStartOfDay(userZone).toInstant().toString();
            String targetLanguage = config.getOrDefault("targetLanguage", AttributeValue.fromS(defaultLanguage)).s();
            var queryPaginator = dbClient.queryPaginator(QueryRequest.builder()
                    .tableName(tableName).indexName("StatusDateIndex")
                    .keyConditionExpression("#s = :statusValue AND createdAt BETWEEN :since AND :until")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(Map.of(
                            ":statusValue", AttributeValue.builder().s("REJECTED").build(),
                            ":since", AttributeValue.builder().s(sinceDateStr).build(),
                            ":until", AttributeValue.builder().s(untilDateStr).build()
                    )).build());
            Map<String, Long> reasonCounts = queryPaginator.items().stream()
                    .filter(item -> item.containsKey("reason"))
                    .map(item -> item.get("reason").s())
                    .collect(Collectors.groupingBy(r -> r, Collectors.counting()));
            String reportContent;
            if (reasonCounts.isEmpty()) {
                reportContent = getEmptyReportByLanguage(targetLanguage, reportDateRange);
            } else {
                String summarizedReasons = reasonCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(50)
                        .map(e -> String.format("%s (發生 %d 次)", e.getKey(), e.getValue()))
                        .collect(Collectors.joining("\n- "));
                long totalCount = reasonCounts.values().stream().mapToLong(Long::longValue).sum();
                int minChar = 1000;
                int maxChar = 1500;
                if ("PERIODIC".equals(strategy)) {
                    String pType = config.getOrDefault("periodType", AttributeValue.fromS("WEEK")).s().trim().toUpperCase();
                    if ("MONTH".equals(pType)) { minChar = 1500; maxChar = 2000; }
                    else if ("YEAR".equals(pType)) { minChar = 2000; maxChar = 3000; }
                } else if ("SPECIFIC".equals(strategy)) {
                    if (config.containsKey("dayOfMonth")) { minChar = 1500; maxChar = 2000; }
                    else if (config.containsKey("monthOfYear")) { minChar = 2000; maxChar = 3000; }
                }
                String trendPrompt = generatePromptFromCounts(totalCount, summarizedReasons, targetLanguage, minChar, maxChar);
                String summary = fraudService.invokeNova(trendPrompt, 0.3);
                reportContent = formatReport(summary, userZone, targetLanguage);
            }
            sendEmail(recipientEmail, "🛡️ SentinelStream Report | " + reportDateRange, reportContent, targetLanguage);
            return "SUCCESS: 報告已發送 (" + reportDateRange + ")";
        } catch (Exception e) {
            logger.log("CRITICAL ERROR: " + e.toString());
            return "FAILED: " + e.getMessage();
        }
    }
    private LocalDate calculateDataStartDate(Map<String, AttributeValue> config, LocalDate today, LocalTime nowTime, LocalTime sendTime) {
        String strategy = config.getOrDefault("strategy", AttributeValue.fromS("PERIODIC")).s().trim().toUpperCase();
        switch (strategy) {
            case "INTERVAL":
                return LocalDate.parse(config.get("startDate").s());
            case "PERIODIC":
                String pType = config.getOrDefault("periodType", AttributeValue.fromS("WEEK")).s().trim().toUpperCase();
                LocalDate targetP;
                if ("WEEK".equals(pType)) {
                    targetP = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                } else if ("MONTH".equals(pType)) {
                    targetP = today.withDayOfMonth(1);
                } else {
                    targetP = today.withDayOfYear(1);
                }
                if (today.equals(targetP) && !nowTime.isBefore(sendTime)) {
                    if ("WEEK".equals(pType)) return targetP.minusWeeks(1);
                    if ("MONTH".equals(pType)) return targetP.minusMonths(1);
                    return targetP.minusYears(1);
                }
                return targetP;
            case "SPECIFIC":
                if (config.containsKey("dayOfWeek")) {
                    DayOfWeek targetDay = DayOfWeek.valueOf(config.get("dayOfWeek").s().trim().toUpperCase());
                    LocalDate target = today.with(TemporalAdjusters.previousOrSame(targetDay));
                    if (today.equals(target)) {
                        return target.minusWeeks(1);
                    }
                    return today.with(TemporalAdjusters.nextOrSame(targetDay));
                } else if (config.containsKey("dayOfMonth")) {
                    int dayNum = Integer.parseInt(config.get("dayOfMonth").n());
                    LocalDate target = today.withDayOfMonth(Math.min(dayNum, today.lengthOfMonth()));
                    if (today.equals(target)) {
                        LocalDate lastMonth = today.minusMonths(1);
                        return lastMonth.withDayOfMonth(Math.min(dayNum, lastMonth.lengthOfMonth()));
                    }
                    return today.isBefore(target) ? target : target.plusMonths(1);
                } else {
                    int monthNum = Integer.parseInt(config.get("monthOfYear").n());
                    LocalDate target = today.withMonth(monthNum).withDayOfMonth(1);
                    if (today.equals(target)) {
                        return target.minusYears(1);
                    }
                    return today.isBefore(target) ? target : target.plusYears(1);
                }
            default:
                return today.minusDays(7);
        }
    }
    private ZonedDateTime calculateNextRun(Map<String, AttributeValue> config, ZonedDateTime now, LocalTime sendTime) {
        String strategy = config.getOrDefault("strategy", AttributeValue.fromS("PERIODIC")).s().trim().toUpperCase();
        ZonedDateTime comparisonNow = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        ZonedDateTime next;
        switch (strategy) {
            case "INTERVAL":
                LocalDate currentStart = LocalDate.parse(config.get("startDate").s());
                int interval = Integer.parseInt(config.get("intervalDays").n());
                LocalDate theoreticalEndDate = currentStart.plusDays(interval);
                ZonedDateTime theoreticalEndDateTime = theoreticalEndDate.atTime(sendTime).atZone(now.getZone());
                if (theoreticalEndDateTime.isAfter(comparisonNow)) {
                    next = theoreticalEndDateTime;
                } else {
                    return now.plusMinutes(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                }
                break;
            case "PERIODIC":
                String pType = config.getOrDefault("periodType", AttributeValue.fromS("WEEK")).s().trim().toUpperCase();
                if ("WEEK".equals(pType)) {
                    next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
                } else if ("MONTH".equals(pType)) {
                    next = (now.getDayOfMonth() == 1) ? now : now.with(TemporalAdjusters.firstDayOfNextMonth());
                } else {
                    next = (now.getDayOfYear() == 1) ? now : now.with(TemporalAdjusters.firstDayOfNextYear());
                }
                break;
            case "SPECIFIC":
                if (config.containsKey("dayOfWeek")) {
                    DayOfWeek targetDay = DayOfWeek.valueOf(config.get("dayOfWeek").s().trim().toUpperCase());
                    next = now.with(TemporalAdjusters.previousOrSame(targetDay));
                } else if (config.containsKey("dayOfMonth")) {
                    int d = Integer.parseInt(config.get("dayOfMonth").n());
                    next = now.withDayOfMonth(Math.min(d, now.toLocalDate().lengthOfMonth()));
                } else {
                    int m = Integer.parseInt(config.get("monthOfYear").n());
                    next = now.withMonth(m).withDayOfMonth(1);
                }
                break;
            default:
                next = now.plusDays(7);
        }
        ZonedDateTime finalNext = next.with(sendTime).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (!strategy.equals("INTERVAL") && (finalNext.isBefore(comparisonNow) || finalNext.equals(comparisonNow))) {
            if (strategy.equals("SPECIFIC")) {
                if (config.containsKey("dayOfWeek")) {
                    finalNext = finalNext.plusWeeks(1);
                } else if (config.containsKey("dayOfMonth")) {
                    ZonedDateTime tempNext = finalNext.plusMonths(1);
                    int targetD = Integer.parseInt(config.get("dayOfMonth").n());
                    finalNext = tempNext.withDayOfMonth(Math.min(targetD, tempNext.toLocalDate().lengthOfMonth()));
                } else {
                    finalNext = finalNext.plusYears(1);
                }
            } else if (strategy.equals("PERIODIC")) {
                String pType = config.getOrDefault("periodType", AttributeValue.fromS("WEEK")).s().trim().toUpperCase();
                if ("WEEK".equals(pType)) finalNext = finalNext.plusWeeks(1);
                else if ("MONTH".equals(pType)) finalNext = finalNext.plusMonths(1);
                else finalNext = finalNext.plusYears(1);
            } else {
                finalNext = finalNext.plusDays(7);
            }
        }
        return finalNext;
    }
    private void scheduleNext(String lambdaArn, ZonedDateTime nextRun, String zoneId) {
        String atExpression = "at(" + nextRun.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + ")";
        try {
            try {
                schedulerClient.deleteSchedule(DeleteScheduleRequest.builder().name(scheduleName).build());
            } catch (Exception ignore) {}
            schedulerClient.createSchedule(CreateScheduleRequest.builder()
                    .name(scheduleName)
                    .scheduleExpression(atExpression)
                    .scheduleExpressionTimezone(zoneId)
                    .target(Target.builder()
                            .arn(lambdaArn)
                            .roleArn(schedulerRoleArn)
                            .build())
                    .actionAfterCompletion(ActionAfterCompletion.DELETE)
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to update next schedule: " + e.getMessage());
        }
    }
    private String generatePromptFromCounts(long total, String reasons, String targetLanguage, int min, int max) {
        return String.format("""
            You are a Senior Fraud Analysis Expert. Analyze the following summary.
            Total incidents in this period: %d
            Audit Reasons Distribution:
            - %s
            - LANGUAGE: Output the ENTIRE report (including all section headers and labels) in **%s**.
            - SYMBOL TRANSFORMATION (MANDATORY):
              * NO SYMBOL STACKING: Use at most one symbol per line. Do not place multiple emojis or bullets together.
              * HEADERS: Use 【 】 for major sections only.
              * ADAPTIVE SYMBOLS: Select relevant symbols or emojis to maintain clarity and professionalism, without cluttering the report with excessive icons.
            - NO TEXT STYLES: Do NOT use Bold (**) or Italic (_) to emphasize text. Keep all text in plain style.
            - Avoid literal translations like '頂三'.
            - LENGTH CONTROL: The total output MUST be between %d and %d characters.
            """, total, reasons, targetLanguage, min, max);
    }
    private String formatReport(String summary, ZoneId zoneId, String language) {
        String timestamp = ZonedDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String header, timeLabel, statusLine, contentLabel, techNote, disclaimer, footer;
        switch (language) {
            case "Japanese":
                header = "SENTINEL STREAM | AI 詐欺趨勢分析レポート";
                timeLabel = "分析時間：";
                statusLine = "監測狀態：システム正常稼働中";
                contentLabel = "【 AI 深度分析內容 】";
                techNote = "💡 技術説明：このレポートは Amazon Nova AI モデルによって自動生成されました。";
                disclaimer = "⚠️ 免責事項：AI の提案は参考用です。変更前に確認を行ってください。";
                footer = "SentinelStream 監視センター より";
                break;
            case "Korean":
                header = "SENTINEL STREAM | AI 사기 트렌드 분석 보고서";
                timeLabel = "분석 시간：";
                statusLine = "모니터링 상태：시스템 정상 작동 중";
                contentLabel = "【 AI 심층 분석 내용 】";
                techNote = "💡 기술 설명: 이 보고서는 Amazon Nova AI 모델에 의해 자동으로 생성되었습니다.";
                disclaimer = "⚠️ 면책 조항: AI 제안은 참고용입니다. 변경 전 검토를 수행하십시오.";
                footer = "SentinelStream 모니터링 센터 드림";
                break;
            case "French":
                header = "SENTINEL STREAM | Rapport d'analyse des fraudes IA";
                timeLabel = "Heure d'analyse : ";
                statusLine = "État de la surveillance : Système opérationnel";
                contentLabel = "【 Analyse approfondie de l'IA 】";
                techNote = "💡 Note : Ce rapport est généré automatiquement par le modèle Amazon Nova AI.";
                disclaimer = "⚠️ Clause de non-responsabilité : Les suggestions de l'IA sont à titre de référence.";
                footer = "Cordialement, Centre de surveillance SentinelStream";
                break;
            case "SimplifiedChinese":
                header = "SENTINEL STREAM | AI 欺诈趋势分析报告";
                timeLabel = "分析时间：";
                statusLine = "监测状态：系统运作正常";
                contentLabel = "【 AI 深度分析内容 】";
                techNote = "💡 技术说明：本报告由 Amazon Nova AI 模型根据系统即時拒绝记录自动生成。";
                disclaimer = "⚠️ 免责声明：AI 建议仅供技术团队参考，执行重大防御变更前请进行人工覆核。";
                footer = "SentinelStream 监测中心 敬上";
                break;
            case "TraditionalChinese":
                header = "SENTINEL STREAM | AI 詐欺趨勢分析報告";
                timeLabel = "分析時間：";
                statusLine = "監測狀態：系統運作正常";
                contentLabel = "【 AI 深度分析內容 】";
                techNote = "💡 技術說明：本報告由 Amazon Nova AI 模型根據系統即時拒絕記錄自動生成。";
                disclaimer = "⚠️ 免責聲明：AI 建議僅供技術團隊參考，執行重大防禦變更前請進行人工覆核。";
                footer = "SentinelStream 監測中心 敬上";
                break;
            default: // English
                header = "SENTINEL STREAM | AI Fraud Trend Report";
                timeLabel = "Analysis Time: ";
                statusLine = "Monitoring Status: System Operating Normally";
                contentLabel = "【 AI Deep Dive Analysis 】";
                techNote = "💡 Tech Note: Generated automatically by Amazon Nova AI model.";
                disclaimer = "⚠️ Disclaimer: AI suggestions are for reference only. Review before action.";
                footer = "Best Regards, SentinelStream Monitoring Center";
                break;
        }
        String cleanSummary = summary.replace("**", "").replace("###", "").replace("##", "");
        StringBuilder sb = new StringBuilder();
        sb.append("🛡️ ").append(header).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📅 ").append(timeLabel).append(timestamp).append("\n");
        sb.append("📊 ").append(statusLine).append("\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append(contentLabel).append("\n");
        sb.append("------------------------------------------\n");
        sb.append(cleanSummary).append("\n\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(techNote).append("\n");
        sb.append(disclaimer).append("\n");
        sb.append("🌐 ").append(footer);
        return sb.toString();
    }
    private void sendEmail(String recipient, String subject, String content, String language) {
        String footerMsg;
        switch (language) {
            case "Japanese":
                footerMsg = "\n\n------------------------------------------------------------------------------------ \nこのレポートは SentinelStream によって自動生成されました。設定を変更するには、管理者に連絡してください。";
                break;
            case "Korean":
                footerMsg = "\n\n------------------------------------------------------------------------------------ \n이 보고서는 SentinelStream에 의해 자동으로 생성되었습니다. 설정을 변경하려면 관리자에게 문의하십시오.";
                break;
            case "French":
                footerMsg = "\n\n------------------------------------------------------------------------------------ \nCe rapport est généré automatiquement par SentinelStream. Pour modifier vos paramètres, veuillez contacter l'administrateur.";
                break;
            case "SimplifiedChinese":
                footerMsg = "\n\n------------------------------------------------------------------------------------ \n本报告由 SentinelStream 自动生成。如需更改设置，请聯繫管理員。";
                break;
            case "TraditionalChinese":
                footerMsg = "\n\n------------------------------------------------------------------------------------ \n此報告由 SentinelStream 自動生成。如需更改設定，請聯繫管理員。";
                break;
            default: // English
                footerMsg = "\n\n \nThis report is automatically generated by SentinelStream. To manage settings, please contact the administrator.";
                break;
        }
        String fullBody = content + footerMsg;
        try {
            SendEmailRequest emailRequest = SendEmailRequest.builder()
                    .destination(Destination.builder().toAddresses(recipient).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).build())
                            .body(Body.builder()
                                    .text(Content.builder().data(fullBody).build())
                                    .build())
                            .build())
                    .source(sourceEmail)
                    .build();
            sesClient.sendEmail(emailRequest);
            System.out.println("Email sent successfully to: " + recipient);
        } catch (Exception e) {
            System.err.println("SES Error: " + e.getMessage());
            throw new RuntimeException("Failed to send email via SES: " + e.getMessage());
        }
    }
    private String getEmptyReportByLanguage(String language, String dateRange) {
        switch (language) {
            case "TraditionalChinese":
                return String.format(
                        "親愛的使用者：\n\n在此報告區間 (%s) 內，SentinelStream 守護中心未偵測到任何違規或欺詐行為。\n\n🛡️ 狀態：運作正常\n✨ 建議：無需採取任何行動，祝您有愉快的一天！",
                        dateRange);
            case "SimplifiedChinese":
                return String.format(
                        "亲爱的用户：\n\n在此报告区间 (%s) 内，SentinelStream 守护中心未侦测到任何违规或欺诈行为。\n\n🛡️ 状态：运作正常\n✨ 建议：无需采取任何行动，祝您有愉快的一天！",
                        dateRange);
            case "Japanese":
                return String.format(
                        "ユーザー様へ：\n\nこのレポート期間 (%s) 内において、SentinelStream 監視センターは違反や不正行為を検出しませんでした。\n\n🛡️ ステータス：正常稼働中\n✨ アドバイス：対応は不要です。素晴らしい一日をお過ごしください！",
                        dateRange);
            case "Korean":
                return String.format(
                        "사용자 귀하:\n\n이 보고 기간 (%s) 동안 SentinelStream 모니터링 센터에서 위반 또는 사기 행위가 감지되지 않았습니다.\n\n🛡️ 상태: 정상 작동\n✨ 권장 사항: 조치가 필요하지 않습니다. 즐거운 하루 되세요!",
                        dateRange);
            case "French":
                return String.format(
                        "Cher utilisateur :\n\nAu cours de cette période de rapport (%s), le centre de surveillance SentinelStream n'a détecté aucune violation ou activité frauduleuse.\n\n🛡️ État : Fonctionnement normal\n✨ Suggestion : Aucune action requise. Passez une excellente journée !",
                        dateRange);
            default: // English
                return String.format(
                        "Dear User:\n\nWithin this report period (%s), SentinelStream Monitoring Center detected no violations or fraudulent activities.\n\n🛡️ Status: Normal\n✨ Suggestion: No action required. Have a great day!",
                        dateRange);
        }
    }
}
