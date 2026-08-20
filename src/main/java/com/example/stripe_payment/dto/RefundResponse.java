package com.example.stripe_payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RefundResponse {
    private String refundId;
    private String status;
    private Long amount;
    private String currency;
    private String message;
}
