package com.example.stripe_payment.exception;

import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;


@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Lỗi từ Stripe (vd coupon không tồn tại -> resource_missing, HTTP 400).
    @ExceptionHandler(StripeOperationException.class)
    public ProblemDetail handleStripe(StripeOperationException ex) {
        StripeException se = ex.getStripeException();
        HttpStatus status = mapStripeStatus(se.getStatusCode());

        log.warn("Stripe error: code={}, httpStatus={}, requestId={}, message={}",
                se.getCode(), se.getStatusCode(), se.getRequestId(), se.getMessage());

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, cleanMessage(se));
        pd.setTitle("Payment provider error");
        if (se.getCode() != null) {
            pd.setProperty("code", se.getCode());
        }
        if (se.getRequestId() != null) {
            pd.setProperty("requestId", se.getRequestId());
        }
        return pd;
    }

    // Dữ liệu client sai (thiếu tham số, trạng thái không hợp lệ) -> 400.
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ProblemDetail handleBadRequest(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Thiếu HTTP header bắt buộc (vd Idempotency-Key) -> 400.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Thiếu header bắt buộc: " + ex.getHeaderName());
    }

    // Không tìm thấy đơn -> 404.
    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // Tài nguyên tĩnh không tồn tại (vd trình duyệt tự xin /favicon.ico) -> 404,
    // KHÔNG log error. Đây là request vô hại, không phải sự cố hệ thống.
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                "No static resource: " + ex.getResourcePath());
    }

    // Còn lại -> 500, KHÔNG lộ chi tiết nội bộ ra ngoài.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        // Các exception khung của Spring (ErrorResponse) đã tự mang status/thông
        // điệp chuẩn -> tôn trọng, không ép thành 500 và không log như lỗi thật.
        if (ex instanceof ErrorResponse er) {
            return er.getBody();
        }
        log.error("Lỗi không xử lý riêng", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Có lỗi xảy ra, vui lòng thử lại.");
    }

    // Map HTTP status Stripe trả về sang status ta trả cho client.
    private HttpStatus mapStripeStatus(int stripeStatus) {
        return switch (stripeStatus) {
            case 400, 404 -> HttpStatus.BAD_REQUEST;      // input sai (coupon/thẻ không tồn tại...)
            case 402 -> HttpStatus.PAYMENT_REQUIRED;      // thẻ bị từ chối
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;     // rate limit
            case 401, 403 -> HttpStatus.INTERNAL_SERVER_ERROR; // lỗi API key/cấu hình PHÍA TA
            default -> HttpStatus.BAD_GATEWAY;            // 5xx / lỗi phía Stripe
        };
    }

    // Bỏ phần "request-id: ..." ở đuôi message của Stripe cho gọn.
    private String cleanMessage(StripeException se) {
        String msg = se.getMessage();
        if (msg == null) {
            return "Payment error";
        }
        int idx = msg.indexOf("; request-id:");
        return idx > 0 ? msg.substring(0, idx) : msg;
    }
}
