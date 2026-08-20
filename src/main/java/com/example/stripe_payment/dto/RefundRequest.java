package com.example.stripe_payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RefundRequest {
    // Cách 1: đưa sessionId (cs_...) -> service tự tra ra paymentIntent.
    private String sessionId;
    // Cách 2: đưa thẳng paymentIntentId (pi_...) nếu bạn đã lưu.
    private String paymentIntentId;
    // Số tiền hoàn (đơn vị nhỏ nhất). Bỏ trống = hoàn toàn phần.
    private Long amount;
}
