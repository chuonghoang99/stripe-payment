package com.example.stripe_payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CouponResponse {
    private String couponId;       // dùng cho couponId ở /checkout, /subscribe
    private String name;
    private Long percentOff;
    private Long amountOff;
    private String currency;
    private String duration;
    private String promotionCode;  // mã khách gõ (nếu có tạo)
    private String message;
}
