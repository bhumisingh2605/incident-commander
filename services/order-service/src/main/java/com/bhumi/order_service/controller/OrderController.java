package com.bhumi.order_service.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@RestController
public class OrderController {

    public record OrderRequest(String item, double amount) {}

    private final RestClient payment;

    public OrderController(@Value("${payment.url}") String paymentUrl) {
        this.payment = RestClient.builder().baseUrl(paymentUrl).build();
    }

    @PostMapping("/orders")
    public Map<String, Object> create(@RequestBody OrderRequest req) {
        String orderId = UUID.randomUUID().toString();
        Map<?, ?> result = payment.post().uri("/pay")
                .body(Map.of("orderId", orderId, "amount", req.amount()))
                .retrieve().body(Map.class);
        return Map.of("orderId", orderId, "item", req.item(), "payment", result);
    }
}
