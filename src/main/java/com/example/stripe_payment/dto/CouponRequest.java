package com.example.stripe_payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CouponRequest {
    // Chọn MỘT trong hai kiểu giảm:
    private Double percentOff;   // vd 20 = giảm 20%
    private Long amountOff;      // vd 50000 = giảm 50.000 (đơn vị nhỏ nhất)
    private String currency;     // bắt buộc nếu dùng amountOff (vd "vnd")

    // Thời hạn: "once" (mặc định), "forever", "repeating".
    private String duration;
    private Long durationInMonths;   // bắt buộc nếu duration = "repeating"

    private String name;             // tên hiển thị (tùy chọn)
    private Long maxRedemptions;     // giới hạn số lượt dùng (tùy chọn)

    // Nếu điền, tạo luôn một promotion code (mã khách gõ) trỏ tới coupon này.
    private String promotionCode;    // vd "SALE20"
}
