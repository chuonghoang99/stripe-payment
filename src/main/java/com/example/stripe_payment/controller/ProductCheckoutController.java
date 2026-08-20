package com.example.stripe_payment.controller;

import com.example.stripe_payment.dto.CouponRequest;
import com.example.stripe_payment.dto.CouponResponse;
import com.example.stripe_payment.dto.ProductRequest;
import com.example.stripe_payment.dto.RefundRequest;
import com.example.stripe_payment.dto.RefundResponse;
import com.example.stripe_payment.dto.StripeResponse;
import com.example.stripe_payment.service.OrderService;
import com.example.stripe_payment.service.StripeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product/v1")
@RequiredArgsConstructor
public class ProductCheckoutController {

    private final OrderService orderService;
    private final StripeService stripeService;

    // Thanh toán một lần. Header Idempotency-Key chống tạo trùng khi retry.
    @PostMapping("/checkout")
    public ResponseEntity<StripeResponse> checkout(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ProductRequest request) {
        return ResponseEntity.ok(orderService.checkout(request, idempotencyKey, false));
    }

    // Đăng ký gói định kỳ (subscription).
    @PostMapping("/subscribe")
    public ResponseEntity<StripeResponse> subscribe(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ProductRequest request) {
        return ResponseEntity.ok(orderService.checkout(request, idempotencyKey, true));
    }

    // Hoàn tiền theo sessionId hoặc paymentIntentId.
    @PostMapping("/refund")
    public ResponseEntity<RefundResponse> refund(@RequestBody RefundRequest request) {
        return ResponseEntity.ok(stripeService.refund(request));
    }

    // Tạo coupon (và tùy chọn promotion code).
    @PostMapping("/coupon")
    public ResponseEntity<CouponResponse> coupon(@RequestBody CouponRequest request) {
        return ResponseEntity.ok(stripeService.createCoupon(request));
    }
}
