# Stripe — Các tính năng nâng cao (ví dụ + giải thích)

Tài liệu này giải thích chi tiết 4 tính năng quan trọng thường gặp khi tích hợp
Stripe với Spring Boot, kèm ví dụ code.

> ✅ **Đã triển khai vào project.** Các tính năng dưới đây giờ là endpoint thật
> trong `StripeService` + `ProductCheckoutController`. Xem mục
> [Test các endpoint](#test-các-endpoint) ở cuối để chạy thử. Phần giải thích bên
> dưới vẫn giữ dạng snippet để làm rõ từng khái niệm.

Thứ tự:
1. [Hoàn tiền (Refund)](#1-hoàn-tiền-refund)
2. [Metadata + Idempotency](#2-metadata--idempotency)
3. [Thanh toán định kỳ (Subscription)](#3-thanh-toán-định-kỳ-subscription)
4. [Mã giảm giá (Coupon / Promotion code)](#4-mã-giảm-giá-coupon--promotion-code)

> Nhắc lại: mọi lời gọi API đều cần `Stripe.apiKey` đã được set (trong demo là
> `@PostConstruct` của `StripeService`).

---

## 1. Hoàn tiền (Refund)

### Khái niệm
Khi khách đã trả tiền, bạn có thể **hoàn lại** toàn phần hoặc một phần. Điểm cần
hiểu về cấu trúc đối tượng của Stripe:

```
Checkout Session  ──tạo ra──▶  PaymentIntent  ──tạo ra──▶  Charge
     (cs_...)                     (pi_...)                   (ch_...)
```

Bạn **không hoàn tiền trên Session**, mà hoàn trên **PaymentIntent** (hoặc Charge).
Vì vậy quy trình thực tế là:

1. Khi webhook `checkout.session.completed` về → lấy `session.getPaymentIntent()`
   (chuỗi `pi_...`) và **lưu vào DB** cùng với `orderId`.
2. Khi cần hoàn tiền → gọi Refund API với `pi_...` đó.

### Ví dụ code
```java
import com.stripe.model.Refund;
import com.stripe.param.RefundCreateParams;

// Hoàn TOÀN PHẦN: bỏ trống amount
public Refund refundFull(String paymentIntentId) throws StripeException {
    RefundCreateParams params = RefundCreateParams.builder()
            .setPaymentIntent(paymentIntentId)                 // pi_...
            .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
            .build();
    return Refund.create(params);
}

// Hoàn MỘT PHẦN: chỉ định amount (đơn vị nhỏ nhất, giống lúc tạo Session)
public Refund refundPartial(String paymentIntentId, long amount) throws StripeException {
    RefundCreateParams params = RefundCreateParams.builder()
            .setPaymentIntent(paymentIntentId)
            .setAmount(amount)                                 // vd 50000 = hoàn 50.000₫
            .build();
    return Refund.create(params);
}
```

### Giải thích
- `setPaymentIntent(pi_...)`: đối tượng cần hoàn. Có thể dùng `.setCharge(ch_...)`
  thay thế nếu bạn giữ charge id.
- `setAmount(...)`: **có** = hoàn một phần; **không có** = hoàn toàn bộ số còn lại.
  Không thể hoàn quá số đã thu; có thể gọi nhiều lần miễn tổng ≤ số gốc.
- `setReason(...)`: lý do (`REQUESTED_BY_CUSTOMER`, `DUPLICATE`, `FRAUDULENT`) —
  tùy chọn, chỉ để thống kê.

### Webhook liên quan
Sau khi hoàn, Stripe bắn: **`charge.refunded`** (và `refund.created` /
`refund.updated`). Nên lắng nghe để cập nhật trạng thái đơn = "đã hoàn tiền",
thay vì tin vào kết quả trả về tức thời của API.

### Lưu ý
- Tiền hoàn về thẻ khách thường mất 5–10 ngày (do ngân hàng), dù Stripe xử lý ngay.
- Với Test mode, refund diễn ra tức thì, không có tiền thật.

---

## 2. Metadata + Idempotency

Hai kỹ thuật "nền tảng" giúp tích hợp đáng tin cậy trong thực tế.

### 2a. Metadata — gắn dữ liệu nội bộ của bạn vào đối tượng Stripe

**Vấn đề:** Stripe không biết gì về `orderId`, `userId` trong hệ thống của bạn.
Khi webhook về, làm sao biết session này ứng với đơn nào?

**Giải pháp:** đính kèm **metadata** (cặp key–value) lúc tạo Session:

```java
SessionCreateParams params = SessionCreateParams.builder()
        .setMode(SessionCreateParams.Mode.PAYMENT)
        .setSuccessUrl(baseUrl + "/success")
        .setCancelUrl(baseUrl + "/cancel")
        .addLineItem(lineItem)
        .putMetadata("orderId", "ORD-12345")     // dữ liệu của BẠN
        .putMetadata("userId", "u_789")
        .build();
```

Khi webhook `checkout.session.completed` về, đọc lại:
```java
Session session = (Session) event.getDataObjectDeserializer().getObject().orElseThrow();
String orderId = session.getMetadata().get("orderId");   // "ORD-12345"
// -> cập nhật đúng đơn hàng trong DB
```

Metadata được lưu cùng đối tượng và **hiện cả trong Dashboard**, rất tiện để tra cứu.
Giới hạn: tối đa 50 khóa/đối tượng, key ≤ 40 ký tự, value ≤ 500 ký tự.

### 2b. Tra cứu lại Session (retrieve) và mở rộng (expand)

Bất cứ lúc nào cũng có thể lấy lại đối tượng bằng id:
```java
Session session = Session.retrieve("cs_test_...");
String status = session.getPaymentStatus();   // "paid", "unpaid", ...
```

Mặc định các trường liên kết chỉ là **id dạng chuỗi** (vd `payment_intent` là
`"pi_..."`). Muốn lấy luôn cả **object** con, dùng **expand**:
```java
import com.stripe.param.checkout.SessionRetrieveParams;

SessionRetrieveParams params = SessionRetrieveParams.builder()
        .addExpand("payment_intent")     // lấy nguyên object PaymentIntent
        .addExpand("line_items")         // lấy danh sách dòng hàng
        .build();
Session s = Session.retrieve("cs_test_...", params, null);

PaymentIntent pi = s.getPaymentIntentObject();   // giờ không null
```
`expand` giúp tránh phải gọi API nhiều lần, nhưng chỉ nên expand thứ bạn cần.

### 2c. Idempotency — chống tạo trùng

**Vấn đề:** người dùng bấm "Thanh toán" hai lần, hoặc mạng chập chờn khiến client
gửi lại request → bạn có nguy cơ tạo **2 Session / thu tiền 2 lần**.

**Giải pháp:** gửi kèm một **Idempotency-Key**. Nếu Stripe nhận lại request có cùng
key, nó **trả về kết quả cũ** thay vì tạo mới:

```java
import com.stripe.net.RequestOptions;

RequestOptions options = RequestOptions.builder()
        .setIdempotencyKey("checkout-ORD-12345")   // gắn theo nghiệp vụ (orderId)
        .build();

Session session = Session.create(params, options);
```

### Giải thích
- Key nên **gắn với thao tác nghiệp vụ** (vd một orderId = một lần checkout), không
  phải random mỗi lần gọi — như vậy retry mới nhận lại đúng kết quả cũ.
- Stripe nhớ key trong **24 giờ**.
- Áp dụng được cho hầu hết lời gọi tạo mới (create Session, Refund, PaymentIntent…).
- Đây là biện pháp cho **phía tạo request**. Ở **phía webhook**, chống trùng bằng
  cách lưu `event.getId()` đã xử lý (Stripe có thể gửi lại cùng một event).

---

## 3. Thanh toán định kỳ (Subscription)

### Khái niệm
Thay vì thu một lần (`Mode.PAYMENT`), Subscription thu **định kỳ** (hàng
tháng/năm) cho các gói thuê bao. Điểm khác cốt lõi: giá phải có **chu kỳ lặp
(recurring)**, và Session chạy ở **`Mode.SUBSCRIPTION`**.

### Ví dụ code
```java
// 1) Chu kỳ lặp: mỗi THÁNG
var recurring = SessionCreateParams.LineItem.PriceData.Recurring.builder()
        .setInterval(SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
        .build();

// 2) Giá định kỳ: 99.000₫ / tháng cho "Gói Pro"
var priceData = SessionCreateParams.LineItem.PriceData.builder()
        .setCurrency("vnd")
        .setUnitAmount(99000L)
        .setRecurring(recurring)                                  // <-- điểm khác biệt
        .setProductData(
                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                        .setName("Gói Pro")
                        .build())
        .build();

var lineItem = SessionCreateParams.LineItem.builder()
        .setQuantity(1L)
        .setPriceData(priceData)
        .build();

// 3) Mode = SUBSCRIPTION
var params = SessionCreateParams.builder()
        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)           // <-- khác PAYMENT
        .setSuccessUrl(baseUrl + "/success")
        .setCancelUrl(baseUrl + "/cancel")
        .addLineItem(lineItem)
        .build();

Session session = Session.create(params);
```
Phần còn lại (redirect sang `session.getUrl()`) giống hệt luồng thanh toán một lần.

### Giải thích
- `setRecurring(...)` biến giá thành "giá định kỳ". Thiếu nó mà để
  `Mode.SUBSCRIPTION` → Stripe báo lỗi.
- Trong thực tế, gói thuê bao thường được **tạo sẵn Product + Price trong
  Dashboard**, rồi Checkout chỉ tham chiếu `.setPrice("price_...")` thay vì dựng
  `priceData` inline như trên.
- Stripe tự động tạo/quản lý một **Customer** và một **Subscription** cho khách.

### Webhook liên quan (quan trọng hơn nhiều so với thanh toán một lần)
Với subscription, vòng đời gói được phản ánh qua các event — nên lắng nghe:

| Event | Ý nghĩa |
|---|---|
| `checkout.session.completed` | Khách đăng ký thành công lần đầu |
| `customer.subscription.created` | Gói được tạo |
| `invoice.paid` | Một kỳ đã thu tiền thành công (gia hạn) |
| `invoice.payment_failed` | Thu tiền kỳ mới thất bại (thẻ hết hạn…) → nên nhắc khách |
| `customer.subscription.updated` | Đổi gói / trạng thái |
| `customer.subscription.deleted` | Gói bị hủy → thu hồi quyền truy cập |

→ Quyền "được dùng dịch vụ" nên bật/tắt **dựa trên các event này**, không dựa trên
lần thanh toán đầu tiên.

### Hủy / quản lý gói
```java
import com.stripe.model.Subscription;

Subscription sub = Subscription.retrieve("sub_...");
sub.cancel();     // hủy ngay
```
Hoặc bật **Customer Portal** của Stripe để khách tự quản lý (đổi thẻ, hủy gói) —
Stripe dựng sẵn giao diện, bạn chỉ tạo một portal session và redirect tới.

---

## 4. Mã giảm giá (Coupon / Promotion code)

### Khái niệm
- **Coupon**: định nghĩa mức giảm (giảm % hoặc giảm số tiền cố định) và thời hạn.
- **Promotion code**: một **mã chữ** (vd `SALE20`) mà khách gõ vào, trỏ tới một
  coupon. Coupon là "quy tắc giảm", promotion code là "mã để khách nhập".

Có 2 cách áp dụng vào Checkout.

### Cách A — Áp coupon sẵn cho Session (khách không cần gõ gì)

Tạo coupon một lần (thường làm sẵn trong Dashboard, hoặc bằng code):
```java
import com.stripe.model.Coupon;
import com.stripe.param.CouponCreateParams;
import java.math.BigDecimal;

Coupon coupon = Coupon.create(
        CouponCreateParams.builder()
                .setPercentOff(new BigDecimal("20"))              // giảm 20%
                .setDuration(CouponCreateParams.Duration.ONCE)    // áp 1 lần
                .setName("GIAM20")
                .build());
// coupon.getId() -> vd "abc123"
```

Gắn coupon đó vào Session:
```java
var params = SessionCreateParams.builder()
        .setMode(SessionCreateParams.Mode.PAYMENT)
        .setSuccessUrl(baseUrl + "/success")
        .setCancelUrl(baseUrl + "/cancel")
        .addLineItem(lineItem)
        .addDiscount(                                             // áp giảm giá
                SessionCreateParams.Discount.builder()
                        .setCoupon(coupon.getId())
                        .build())
        .build();
```
→ Trang Checkout tự hiển thị giá đã giảm, khách không phải nhập mã.

### Cách B — Cho khách tự nhập mã trên trang Checkout

```java
var params = SessionCreateParams.builder()
        // ... như trên nhưng KHÔNG dùng addDiscount ...
        .setAllowPromotionCodes(true)     // hiện ô "Add promotion code"
        .build();
```
Rồi tạo promotion code trỏ tới coupon:
```java
import com.stripe.model.PromotionCode;
import com.stripe.param.PromotionCodeCreateParams;

PromotionCode.create(
        PromotionCodeCreateParams.builder()
                .setCoupon(coupon.getId())
                .setCode("SALE20")        // mã khách sẽ gõ (không phân biệt hoa/thường)
                .build());
```
→ Trên trang Checkout, khách gõ `SALE20` để được giảm.

### Giải thích & lưu ý
- **`addDiscount` và `setAllowPromotionCodes(true)` loại trừ nhau** — chọn một
  trong hai cho mỗi Session (áp sẵn HOẶC để khách nhập).
- `Duration`:
  - `ONCE`: giảm cho lần thanh toán này.
  - `FOREVER` / `REPEATING` (kèm `durationInMonths`): dùng cho **subscription** —
    giảm mãi mãi hoặc trong N tháng đầu.
- Giảm số tiền cố định: dùng `.setAmountOff(50000L).setCurrency("vnd")` thay cho
  `setPercentOff(...)`.
- Có thể giới hạn: `setMaxRedemptions(100)` (tối đa 100 lượt dùng),
  `setRedeemBy(timestamp)` (hết hạn).

---

## Tổng kết nhanh

| Tính năng | Đối tượng/tham số chính | Webhook nên nghe |
|---|---|---|
| Refund | `Refund.create` trên `pi_...` | `charge.refunded` |
| Metadata | `.putMetadata(k, v)` → đọc lại trong webhook | (dùng chung) |
| Idempotency | `RequestOptions` + `setIdempotencyKey` | — |
| Subscription | `Mode.SUBSCRIPTION` + `Recurring` | `invoice.paid`, `invoice.payment_failed`, `customer.subscription.*` |
| Coupon | `addDiscount` hoặc `setAllowPromotionCodes(true)` | — |

---

## Test các endpoint

Tất cả nằm dưới base path `/product/v1`. Nhớ đặt `STRIPE_SECRET_KEY` và chạy app
(`./mvnw spring-boot:run`) trước.

### Giao diện web (`http://localhost:8080`)
Form đã có sẵn: ô **Coupon ID**, checkbox **cho khách tự nhập mã**, chọn **chu kỳ**,
và 2 nút **"Thanh toán 1 lần"** (`/checkout`) / **"Đăng ký định kỳ"** (`/subscribe`).
`orderId` được tự sinh và hiển thị cuối trang (dùng làm idempotency key).

### 0) Tạo coupon (để có `couponId` dùng ở bước 1)
```bash
# Giảm 20%, dùng 1 lần, tạo luôn promotion code "SALE20"
curl -X POST http://localhost:8080/product/v1/coupon \
  -H "Content-Type: application/json" \
  -d '{"percentOff":20,"duration":"once","name":"Giảm 20%","promotionCode":"SALE20"}'

# Hoặc giảm số tiền cố định 50.000₫
curl -X POST http://localhost:8080/product/v1/coupon \
  -H "Content-Type: application/json" \
  -d '{"amountOff":50000,"currency":"vnd","duration":"once","name":"Giảm 50k"}'
```
Kết quả trả về `couponId` (vd `"couponId":"abc123"`) — copy để dùng ở bước 1, hoặc
dùng promotion code `SALE20` bằng cách bật "cho khách tự nhập mã".

Quy tắc: đúng **một** trong `percentOff`/`amountOff`; `amountOff` phải kèm
`currency`; `duration` là `once`/`forever`/`repeating` (`repeating` cần
`durationInMonths`).

### 1) Thanh toán một lần + coupon + metadata + idempotency
```bash
curl -X POST http://localhost:8080/product/v1/checkout \
  -H "Content-Type: application/json" \
  -d '{"name":"Áo thun","amount":150000,"quantity":1,"currency":"vnd",
       "orderId":"ORD-1001","couponId":"","allowPromotionCodes":true}'
```
Gọi lại đúng lệnh này (cùng `orderId`) sẽ trả về **cùng một Session** nhờ
idempotency key `checkout-ORD-1001`.

### 2) Đăng ký định kỳ (subscription)
```bash
curl -X POST http://localhost:8080/product/v1/subscribe \
  -H "Content-Type: application/json" \
  -d '{"name":"Gói Pro","amount":99000,"quantity":1,"currency":"vnd",
       "interval":"month","orderId":"SUB-2001"}'
```
Mở `sessionUrl` trả về, thanh toán bằng thẻ test `4242 4242 4242 4242`.

### 3) Hoàn tiền
Lấy `sessionId` (cs_...) từ kết quả checkout ở trên (sau khi đã thanh toán xong):
```bash
# Hoàn toàn phần
curl -X POST http://localhost:8080/product/v1/refund \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"cs_test_..."}'

# Hoàn một phần (vd 50.000₫)
curl -X POST http://localhost:8080/product/v1/refund \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"cs_test_...","amount":50000}'
```
Kết quả: `{"refundId":"re_...","status":"succeeded","amount":50000,...}`. Trong log
app sẽ thấy webhook `charge.refunded` nếu đang chạy `stripe listen`.

> ⚠️ Chỉ hoàn được sau khi Session đã **thanh toán xong** (có `paymentIntent`).
> Nếu đưa sessionId của phiên chưa trả tiền, service báo lỗi rõ ràng.
