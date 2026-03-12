package com.sentinel.sentinel_stream.controller;

import com.sentinel.sentinel_stream.entity.Order;
import com.sentinel.sentinel_stream.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.json.JSONObject;

// 串接AI後加上的
import com.sentinel.sentinel_stream.service.FraudDetectionService;
// 日誌紀錄(Logging)
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j // 自動產生 log 變數
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private FraudDetectionService fraudDetectionService;

    @Autowired
    private OrderRepository orderRepository; // 串接AI後加上的

    // 1. 模擬接收新訂單 (由全球電商系統打過來)
    @PostMapping("/submit")
    public Order submitOrder(@RequestBody Order order) {
        log.info("收到新訂單請求: User={}, Amount={}", order.getUserId(), order.getAmount());

        order.setStatus("PENDING");

        // 呼叫 AI 前記錄
        log.debug("正在呼叫 Amazon Bedrock (Claude 3.5 Haiku) 進行風險評估...");

        // 這裡呼叫真正的 AI！
        // 1. 接收整個 JSON 物件
        JSONObject aiResponse = fraudDetectionService.analyzeRisk(order);

        // 2. 從 JSON 中分別取出分數和理由
        double score = aiResponse.getDouble("risk_score");
        String reason = aiResponse.getString("reason");

        // 3. 把結果存進 order 物件
        order.setRiskScore(score);
        order.setRiskReason(reason);

        if (score > 0.7) {
            log.warn("偵測到高風險訂單！理由: {}", reason);
            order.setStatus("REJECTED (AI High Risk)");
        } else {
            order.setStatus("APPROVED");
        }

        return orderRepository.save(order);
    }

    // 2. 查詢所有訂單 (管理後台用)
    @GetMapping("/all") // 加上路由，不然外面連不到這個方法
    @Transactional(readOnly = true) // 告訴資料庫這只是讀取，不需要處理事務鎖定，效能更好
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
}
