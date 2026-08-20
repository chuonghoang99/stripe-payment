package com.example.stripe_payment.service;

import com.example.stripe_payment.dto.OrderResponse;
import com.example.stripe_payment.dto.ProductRequest;
import com.example.stripe_payment.dto.StripeResponse;
import com.example.stripe_payment.entity.OrderEntity;
import com.example.stripe_payment.entity.OrderStatus;
import com.example.stripe_payment.entity.ProcessedEventEntity;
import com.example.stripe_payment.exception.OrderNotFoundException;
import com.example.stripe_payment.exception.StripeOperationException;
import com.example.stripe_payment.repository.OrderRepository;
import com.example.stripe_payment.repository.ProcessedEventRepository;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final StripeService stripeService;

    // ---------------------------------------------------------------------
    // Tạo checkout với idempotency 2 tầng (DB unique key + Stripe Idempotency-Key)
    // ---------------------------------------------------------------------
    public StripeResponse checkout(ProductRequest request, String idempotencyKey, boolean subscription) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Thiếu header Idempotency-Key");
        }

        // (1) Đã có đơn cho key này -> trả lại session cũ, KHÔNG tạo mới.
        Optional<OrderEntity> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("↩️ Idempotency-Key {} đã tồn tại -> trả về đơn cũ {}",
                    idempotencyKey, existing.get().getOrderRef());
            return toResponse(existing.get(), true);
        }

        // (2) Tạo bản ghi đơn PENDING. Unique constraint chặn tạo trùng khi đua tranh.
        OrderEntity order = new OrderEntity();
        order.setOrderRef(resolveOrderRef(request));
        order.setIdempotencyKey(idempotencyKey);
        order.setProductName(request.getName());
        order.setAmount(request.getAmount());
        order.setQuantity(request.getQuantity());
        order.setCurrency(request.getCurrency());
        order.setMode(subscription ? "subscription" : "payment");
        order.setStatus(OrderStatus.PENDING);
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException race) {
            // Một request đồng thời khác đã thắng -> load lại và trả về đơn của nó.
            OrderEntity won = orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race);
            return toResponse(won, true);
        }

        // (3) Gọi Stripe (kèm cùng Idempotency-Key), rồi lưu session vào đơn.
        try {
            Session session = stripeService.createCheckoutSession(
                    request, subscription, idempotencyKey, order.getOrderRef());
            order.setStripeSessionId(session.getId());
            order.setStripeSessionUrl(session.getUrl());
            order = orderRepository.save(order);

            return StripeResponse.builder()
                    .status(session.getStatus())
                    .message("Success")
                    .sessionId(session.getId())
                    .sessionUrl(session.getUrl())
                    .orderRef(order.getOrderRef())
                    .orderStatus(order.getStatus().name())
                    .duplicate(false)
                    .build();
        } catch (StripeException e) {
            // Đơn vẫn ở trạng thái PENDING (không có session) để dò lỗi sau.
            throw new StripeOperationException(e);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderRef) {
        return orderRepository.findByOrderRef(orderRef)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderRef));
    }

    // ---------------------------------------------------------------------
    // Xử lý webhook: dedupe theo event_id + cập nhật trạng thái đơn.
    // ---------------------------------------------------------------------
    @Transactional
    public void processWebhookEvent(Event event) {
        if (processedEventRepository.existsById(event.getId())) {
            log.info("🔁 Bỏ qua webhook đã xử lý: {}", event.getId());
            return;
        }

        switch (event.getType()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "charge.refunded" -> handleChargeRefunded(event);
            case "invoice.payment_failed" ->
                    log.warn("💸 invoice.payment_failed (event {}).", event.getId());
            case "customer.subscription.deleted" ->
                    log.info("🚫 customer.subscription.deleted (event {}).", event.getId());
            default -> log.info("Bỏ qua sự kiện không xử lý: {}", event.getType());
        }

        ProcessedEventEntity processed = new ProcessedEventEntity();
        processed.setEventId(event.getId());
        processed.setType(event.getType());
        processedEventRepository.save(processed);
    }

    private void handleCheckoutCompleted(Event event) {
        Session session = deserialize(event, Session.class);
        orderRepository.findByStripeSessionId(session.getId()).ifPresentOrElse(order -> {
            order.setStatus(OrderStatus.PAID);
            order.setStripePaymentIntentId(session.getPaymentIntent());
            order.setAmountTotal(session.getAmountTotal());
            order.setCustomerEmail(session.getCustomerEmail());
            orderRepository.save(order);
            log.info("✅ Đơn {} -> PAID (paymentIntent={})",
                    order.getOrderRef(), session.getPaymentIntent());
        }, () -> log.warn("Không tìm thấy đơn cho session {}", session.getId()));
    }

    private void handleChargeRefunded(Event event) {
        Charge charge = deserialize(event, Charge.class);
        if (charge.getPaymentIntent() == null) {
            return;
        }
        orderRepository.findByStripePaymentIntentId(charge.getPaymentIntent()).ifPresent(order -> {
            order.setStatus(OrderStatus.REFUNDED);
            orderRepository.save(order);
            log.info("↩️ Đơn {} -> REFUNDED", order.getOrderRef());
        });
    }

    // Deserialize data object của event, có fallback deserializeUnsafe khi API
    // version của tài khoản Stripe khác version SDK (getObject() rỗng) — tránh
    // âm thầm bỏ qua event khiến đơn không được cập nhật.
    private <T extends StripeObject> T deserialize(Event event, Class<T> type) {
        var deserializer = event.getDataObjectDeserializer();
        StripeObject obj = deserializer.getObject().orElseGet(() -> {
            try {
                return deserializer.deserializeUnsafe();
            } catch (EventDataObjectDeserializationException e) {
                throw new IllegalStateException(
                        "Không deserialize được data của event " + event.getId(), e);
            }
        });
        return type.cast(obj);
    }

    // ============================ Helpers ================================

    private StripeResponse toResponse(OrderEntity order, boolean duplicate) {
        return StripeResponse.builder()
                .status("reused")
                .message(duplicate ? "Duplicate request - returned existing order" : "Success")
                .sessionId(order.getStripeSessionId())
                .sessionUrl(order.getStripeSessionUrl())
                .orderRef(order.getOrderRef())
                .orderStatus(order.getStatus().name())
                .duplicate(duplicate)
                .build();
    }

    private String resolveOrderRef(ProductRequest request) {
        if (StringUtils.hasText(request.getOrderId())) {
            return request.getOrderId();
        }
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
