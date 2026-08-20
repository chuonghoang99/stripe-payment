package com.example.stripe_payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter
@Setter
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_ref", nullable = false)
    private String orderRef;

    // Khóa chống trùng (unique) — lấy từ HTTP header Idempotency-Key.
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "product_name")
    private String productName;

    private Long amount;
    private Long quantity;
    private String currency;

    // "payment" hoặc "subscription"
    private String mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    @Column(name = "stripe_session_url")
    private String stripeSessionUrl;

    // Điền khi webhook checkout.session.completed về — dùng để hoàn tiền.
    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "amount_total")
    private Long amountTotal;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
