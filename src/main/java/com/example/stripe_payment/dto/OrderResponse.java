package com.example.stripe_payment.dto;

import com.example.stripe_payment.entity.OrderEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OrderResponse {
    private String orderRef;
    private String status;
    private String mode;
    private String productName;
    private Long amount;
    private Long quantity;
    private String currency;
    private Long amountTotal;
    private String customerEmail;
    private String stripeSessionId;
    private String stripePaymentIntentId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static OrderResponse from(OrderEntity o) {
        return OrderResponse.builder()
                .orderRef(o.getOrderRef())
                .status(o.getStatus() != null ? o.getStatus().name() : null)
                .mode(o.getMode())
                .productName(o.getProductName())
                .amount(o.getAmount())
                .quantity(o.getQuantity())
                .currency(o.getCurrency())
                .amountTotal(o.getAmountTotal())
                .customerEmail(o.getCustomerEmail())
                .stripeSessionId(o.getStripeSessionId())
                .stripePaymentIntentId(o.getStripePaymentIntentId())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .build();
    }
}
