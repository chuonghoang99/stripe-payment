package com.example.stripe_payment.controller;

import com.example.stripe_payment.service.OrderService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nhận sự kiện webhook từ SERVER của Stripe (server-to-server) — nguồn đáng tin
 * để cập nhật trạng thái đơn. Verify chữ ký, rồi giao cho OrderService xử lý
 * (dedupe theo event_id + cập nhật DB).
 */
@RestController
@RequiredArgsConstructor
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    @Value("${stripe.webhookSecret}")
    private String webhookSecret;

    private final OrderService orderService;

    @PostMapping("/webhook")
    public ResponseEntity<String> handle(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("⚠️ Webhook có chữ ký không hợp lệ: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        orderService.processWebhookEvent(event);
        return ResponseEntity.ok("Received");
    }
}
