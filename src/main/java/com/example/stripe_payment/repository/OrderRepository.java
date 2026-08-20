package com.example.stripe_payment.repository;

import com.example.stripe_payment.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<OrderEntity> findByOrderRef(String orderRef);

    Optional<OrderEntity> findByStripeSessionId(String stripeSessionId);

    Optional<OrderEntity> findByStripePaymentIntentId(String stripePaymentIntentId);
}
