package com.example.stripe_payment.service;

import com.example.stripe_payment.dto.CouponRequest;
import com.example.stripe_payment.dto.CouponResponse;
import com.example.stripe_payment.dto.ProductRequest;
import com.example.stripe_payment.dto.RefundRequest;
import com.example.stripe_payment.dto.RefundResponse;
import com.example.stripe_payment.exception.StripeOperationException;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Coupon;
import com.stripe.model.PromotionCode;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CouponCreateParams;
import com.stripe.param.PromotionCodeCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * Bao bọc thư viện Stripe (chỉ gọi API Stripe, không đụng DB).
 * Việc lưu đơn/điều phối idempotency ở tầng DB do OrderService đảm nhiệm.
 */
@Service
public class StripeService {

    @Value("${stripe.secretKey}")
    private String secretKey;

    @Value("${app.base-url}")
    private String baseUrl;

    @PostConstruct
    void init() {
        Stripe.apiKey = secretKey;
    }

    // Tạo Checkout Session (một lần hoặc định kỳ). Gắn idempotencyKey làm
    // Stripe Idempotency-Key: retry cùng key sẽ trả về đúng session cũ.
    public Session createCheckoutSession(ProductRequest request, boolean subscription,
                                         String idempotencyKey, String orderRef) throws StripeException {
        var lineItem = buildLineItem(request, subscription);

        var builder = SessionCreateParams.builder()
                .setMode(subscription
                        ? SessionCreateParams.Mode.SUBSCRIPTION
                        : SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(baseUrl + "/success")
                .setCancelUrl(baseUrl + "/cancel")
                .addLineItem(lineItem)
                .putMetadata("orderRef", orderRef);

        applyDiscount(builder, request);

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();
        return Session.create(builder.build(), options);
    }

    // HOÀN TIỀN (toàn phần / một phần)
    public RefundResponse refund(RefundRequest request) {
        try {
            String paymentIntentId = request.getPaymentIntentId();
            if (!StringUtils.hasText(paymentIntentId)) {
                if (!StringUtils.hasText(request.getSessionId())) {
                    throw new IllegalArgumentException("Cần sessionId hoặc paymentIntentId");
                }
                Session session = Session.retrieve(request.getSessionId());
                paymentIntentId = session.getPaymentIntent();
                if (!StringUtils.hasText(paymentIntentId)) {
                    throw new IllegalStateException(
                            "Session chưa có paymentIntent (chưa thanh toán xong?)");
                }
            }

            var params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
            if (request.getAmount() != null) {
                params.setAmount(request.getAmount());
            }

            Refund refund = Refund.create(params.build());
            return RefundResponse.builder()
                    .refundId(refund.getId())
                    .status(refund.getStatus())
                    .amount(refund.getAmount())
                    .currency(refund.getCurrency())
                    .message("Refund created")
                    .build();
        } catch (StripeException e) {
            throw new StripeOperationException(e);
        }
    }

    // TẠO COUPON (và tùy chọn promotion code)
    public CouponResponse createCoupon(CouponRequest request) {
        try {
            var params = CouponCreateParams.builder();

            boolean hasPercent = request.getPercentOff() != null;
            boolean hasAmount = request.getAmountOff() != null;
            if (hasPercent == hasAmount) {
                throw new IllegalArgumentException("Cần đúng một trong: percentOff HOẶC amountOff");
            }
            if (hasPercent) {
                params.setPercentOff(BigDecimal.valueOf(request.getPercentOff()));
            } else {
                if (!StringUtils.hasText(request.getCurrency())) {
                    throw new IllegalArgumentException("amountOff cần kèm currency");
                }
                params.setAmountOff(request.getAmountOff()).setCurrency(request.getCurrency());
            }

            params.setDuration(resolveDuration(request));
            if ("repeating".equalsIgnoreCase(request.getDuration())) {
                params.setDurationInMonths(request.getDurationInMonths());
            }
            if (StringUtils.hasText(request.getName())) {
                params.setName(request.getName());
            }
            if (request.getMaxRedemptions() != null) {
                params.setMaxRedemptions(request.getMaxRedemptions());
            }

            Coupon coupon = Coupon.create(params.build());

            String promoCode = null;
            if (StringUtils.hasText(request.getPromotionCode())) {
                PromotionCode pc = PromotionCode.create(
                        PromotionCodeCreateParams.builder()
                                .setCoupon(coupon.getId())
                                .setCode(request.getPromotionCode())
                                .build());
                promoCode = pc.getCode();
            }

            return CouponResponse.builder()
                    .couponId(coupon.getId())
                    .name(coupon.getName())
                    .percentOff(coupon.getPercentOff() != null ? coupon.getPercentOff().longValue() : null)
                    .amountOff(coupon.getAmountOff())
                    .currency(coupon.getCurrency())
                    .duration(coupon.getDuration())
                    .promotionCode(promoCode)
                    .message("Coupon created")
                    .build();
        } catch (StripeException e) {
            throw new StripeOperationException(e);
        }
    }

    // ============================ Helpers ================================

    private SessionCreateParams.LineItem buildLineItem(ProductRequest request, boolean subscription) {
        var productData = SessionCreateParams.LineItem.PriceData.ProductData.builder()
                .setName(request.getName())
                .build();

        var priceBuilder = SessionCreateParams.LineItem.PriceData.builder()
                .setCurrency(request.getCurrency())
                .setUnitAmount(request.getAmount())
                .setProductData(productData);

        if (subscription) {
            priceBuilder.setRecurring(
                    SessionCreateParams.LineItem.PriceData.Recurring.builder()
                            .setInterval(resolveInterval(request.getInterval()))
                            .build());
        }

        return SessionCreateParams.LineItem.builder()
                .setQuantity(request.getQuantity())
                .setPriceData(priceBuilder.build())
                .build();
    }

    private SessionCreateParams.LineItem.PriceData.Recurring.Interval resolveInterval(String interval) {
        if ("year".equalsIgnoreCase(interval)) {
            return SessionCreateParams.LineItem.PriceData.Recurring.Interval.YEAR;
        }
        return SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH;
    }

    private void applyDiscount(SessionCreateParams.Builder builder, ProductRequest request) {
        if (StringUtils.hasText(request.getCouponId())) {
            builder.addDiscount(SessionCreateParams.Discount.builder()
                    .setCoupon(request.getCouponId())
                    .build());
        } else if (Boolean.TRUE.equals(request.getAllowPromotionCodes())) {
            builder.setAllowPromotionCodes(true);
        }
    }

    private CouponCreateParams.Duration resolveDuration(CouponRequest request) {
        String d = request.getDuration();
        if ("forever".equalsIgnoreCase(d)) {
            return CouponCreateParams.Duration.FOREVER;
        }
        if ("repeating".equalsIgnoreCase(d)) {
            if (request.getDurationInMonths() == null) {
                throw new IllegalArgumentException("duration=repeating cần durationInMonths");
            }
            return CouponCreateParams.Duration.REPEATING;
        }
        return CouponCreateParams.Duration.ONCE;
    }
}
