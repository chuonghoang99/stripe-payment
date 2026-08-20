package com.example.stripe_payment.exception;

import com.stripe.exception.StripeException;
import lombok.Getter;

/**
 * Bọc StripeException (checked) thành unchecked nhưng GIỮ nguyên đối tượng gốc,
 * để GlobalExceptionHandler map được sang HTTP status/code chuẩn — thay vì ném
 * RuntimeException trần (khiến FE nhận 500).
 */
@Getter
public class StripeOperationException extends RuntimeException {

    private final transient StripeException stripeException;

    public StripeOperationException(StripeException cause) {
        super(cause.getMessage(), cause);
        this.stripeException = cause;
    }

}
