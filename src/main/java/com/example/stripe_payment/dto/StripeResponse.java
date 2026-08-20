package com.example.stripe_payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StripeResponse {
    private String status;        // trạng thái Stripe Session
    private String message;
    private String sessionId;
    private String sessionUrl;

    // --- Thông tin đơn hàng nội bộ ---
    private String orderRef;      // mã đơn để tra cứu qua GET /orders/{ref}
    private String orderStatus;   // PENDING / PAID / ...
    private boolean duplicate;    // true = request trùng idempotency key -> trả session cũ
}
