package com.sentinel.sentinel_stream.controller;

import com.sentinel.sentinel_stream.entity.Order;
import com.sentinel.sentinel_stream.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.json.JSONObject;

// Added for AI Integration
import com.sentinel.sentinel_stream.service.FraudDetectionService;
// Logging Support
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j // Automatically generates the 'log' variable via Lombok
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private FraudDetectionService fraudDetectionService;

    @Autowired
    private OrderRepository orderRepository; // Added for persistence logic

    // 1. Handle new incoming order requests (Simulated global e-commerce gateway)
    @PostMapping("/submit")
    public Order submitOrder(@RequestBody Order order) {
        log.info("Received new order request: User={}, Amount={}", order.getUserId(), order.getAmount());

        order.setStatus("PENDING");

        // Log before AI invocation
        log.debug("Invoking Amazon Bedrock (Amazon Nova Lite) for risk assessment...");

        // Invoke the actual AI Model!
        // 1. Receive the full JSON response from AI service
        JSONObject aiResponse = fraudDetectionService.analyzeRisk(order);

        // 2. Extract score and reason from the JSON object
        double score = aiResponse.getDouble("risk_score");
        String reason = aiResponse.getString("reason");

        // 3. Map AI results back to the Order object
        order.setRiskScore(score);
        order.setRiskReason(reason);

        if (score > 0.7) {
            log.warn("High-risk order detected! Reason: {}", reason);
            order.setStatus("REJECTED (AI High Risk)");
        } else {
            order.setStatus("APPROVED");
        }

        return orderRepository.save(order);
    }

    // 2. Query all orders (For administrative dashboard/auditing)
    @GetMapping("/all") // Expose endpoint for external dashboard access
    @Transactional(readOnly = true) // Read-only transaction to optimize database performance (avoids lock contention)
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
}
