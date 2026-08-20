package com.example.stripe_payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductRequest {
    private Long amount;
    private Long quantity;
    private String name;
    private String currency;

    // --- Tùy chọn nâng cao ---
    // Mã đơn hàng nội bộ: gắn vào metadata + dùng làm idempotency key.
    private String orderId;
    // Áp sẵn 1 coupon (vd "abc123"). Loại trừ với allowPromotionCodes.
    private String couponId;
    // Cho khách tự nhập mã giảm giá trên trang Checkout.
    private Boolean allowPromotionCodes;
    // Chu kỳ cho thanh toán định kỳ: "month" hoặc "year" (chỉ dùng ở /subscribe).
    private String interval;
}
