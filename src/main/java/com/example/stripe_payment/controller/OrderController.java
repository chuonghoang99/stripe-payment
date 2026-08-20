package com.example.stripe_payment.controller;

import com.example.stripe_payment.dto.OrderResponse;
import com.example.stripe_payment.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // Tra cứu đơn theo mã (order_ref). Không thấy -> 404.
    @GetMapping("/orders/{ref}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String ref) {
        return ResponseEntity.ok(orderService.getOrder(ref));
    }
}
