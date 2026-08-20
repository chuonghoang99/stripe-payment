package com.example.stripe_payment.entity;

public enum OrderStatus {
    PENDING,   // đã tạo đơn + phiên thanh toán, chờ khách trả tiền
    PAID,      // webhook checkout.session.completed đã xác nhận
    FAILED,    // thanh toán thất bại
    REFUNDED   // đã hoàn tiền
}
