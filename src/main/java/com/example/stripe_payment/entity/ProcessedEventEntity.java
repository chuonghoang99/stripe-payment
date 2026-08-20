package com.example.stripe_payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Ghi lại các webhook event đã xử lý để bảo đảm idempotent ở phía webhook:
 * nếu Stripe gửi lại cùng event_id, ta bỏ qua.
 */
@Entity
@Table(name = "processed_webhook_events")
@Getter
@Setter
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id")
    private String eventId;

    private String type;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @PrePersist
    void onCreate() {
        processedAt = LocalDateTime.now();
    }
}
