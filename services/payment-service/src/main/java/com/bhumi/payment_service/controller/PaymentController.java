package com.bhumi.payment_service.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PaymentController {

    public record PayRequest(String orderId, double amount) {}

        @PostMapping("/pay")
        public Map<String, Object> pay(@RequestBody PayRequest req) {
            return Map.of("orderId", req.orderId(), "status", "PAID", "amount", req.amount());
        }
}
