# Lưu đơn hàng (Postgres) + chống thanh toán trùng (Idempotency)

Tài liệu này giải thích tầng lưu trữ mới và cơ chế idempotency 2 tầng.

---

## 1. Vì sao cần

- **Lưu đơn + kết quả sau thanh toán:** trước đây app không nhớ gì. Giờ mỗi lần
  checkout tạo một bản ghi trong bảng `orders`; webhook cập nhật trạng thái
  (`PENDING → PAID/REFUNDED/FAILED`) → có nguồn dữ liệu để tra cứu, đối soát.
- **Chống thanh toán trùng khi retry:** người dùng bấm 2 lần, hoặc client tự gửi
  lại do timeout mạng → phải đảm bảo **chỉ tạo một** phiên thanh toán.

---

## 2. Idempotency 2 tầng

Client sinh một **`Idempotency-Key`** (UUID) và gửi qua **HTTP header** ở mỗi lần
gọi `/checkout` hoặc `/subscribe`. Cùng một thao tác → dùng lại cùng key.

```
Request (header: Idempotency-Key: 9f8c...)
        │
        ▼
[Tầng 1 — DB]  orders.idempotency_key UNIQUE
        │
        ├─ đã tồn tại  ─────▶ trả về session cũ, duplicate=true  (DỪNG, không gọi Stripe)
        │
        └─ chưa có → INSERT order PENDING
                       (nếu 2 request đua nhau, UNIQUE làm 1 cái fail
                        → bắt DataIntegrityViolationException → trả đơn của cái thắng)
        │
        ▼
[Tầng 2 — Stripe]  Session.create(params, Idempotency-Key = cùng key đó)
                    → kể cả nếu lọt tới đây 2 lần, Stripe cũng trả về CÙNG session
        │
        ▼
   Lưu session_id/url vào order, trả duplicate=false
```

- **Tầng 1 (DB)** chặn ngay từ ứng dụng, không tốn thêm lời gọi Stripe.
- **Tầng 2 (Stripe)** là lưới an toàn cuối: ngay cả khi hai request cùng lọt qua
  tầng 1 (đua tranh), Stripe vẫn không tạo 2 phiên nhờ cùng Idempotency-Key.

Ở phía **webhook**, idempotency riêng: mỗi `event_id` đã xử lý được ghi vào
`processed_webhook_events`; Stripe gửi lại cùng event → bỏ qua.

---

## 3. Cấu trúc code

```
controller/ProductCheckoutController   POST /checkout, /subscribe  (đọc header Idempotency-Key)
controller/OrderController             GET  /orders/{ref}
controller/StripeWebhookController      POST /webhook -> OrderService.processWebhookEvent
service/StripeService                  chỉ gọi API Stripe (không đụng DB)
service/OrderService                   điều phối idempotency + lưu DB + xử lý webhook (@Transactional)
entity/OrderEntity, ProcessedEventEntity, OrderStatus
repository/OrderRepository, ProcessedEventRepository
resources/db/migration/V1__init.sql    schema (Flyway)
```

Nguyên tắc tách lớp: **`StripeService` chỉ biết Stripe**, **`OrderService` biết DB
+ nghiệp vụ**. Giữ ranh giới này khi mở rộng.

Schema do **Flyway** quản lý (`V1__init.sql`); Hibernate chạy `ddl-auto=validate`
(chỉ kiểm tra entity khớp bảng, không tự sửa schema).

---

## 4. Chạy

```bash
docker compose up -d      # khởi động Postgres (compose.yaml)
export STRIPE_SECRET_KEY=sk_test_...
export STRIPE_WEBHOOK_SECRET=whsec_...     # từ `stripe listen`
./mvnw spring-boot:run
```
Flyway tự tạo bảng khi app khởi động lần đầu.

> ⚠️ **Lưu ý Spring Boot 4:** autoconfiguration được tách theo module, nên chỉ có
> `flyway-core` là **chưa đủ** để Flyway tự chạy — thiếu nó, Hibernate `validate`
> sẽ báo `missing table [orders]`. Bắt buộc thêm module
> `org.springframework.boot:spring-boot-flyway` (đã có trong `pom.xml`). Kiểm tra
> Flyway có chạy không bằng dòng log `Migrating schema "public" to version "1 - init"`.

---

## 5. Test idempotency bằng curl

Gọi **hai lần** với **cùng** `Idempotency-Key`:
```bash
KEY=$(uuidgen)

# Lần 1 -> tạo đơn mới
curl -s -X POST http://localhost:8080/product/v1/checkout \
  -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
  -d '{"name":"Áo","amount":150000,"quantity":1,"currency":"vnd","orderId":"ORD-1"}'

# Lần 2 (cùng KEY) -> trả về ĐÚNG session cũ, duplicate=true
curl -s -X POST http://localhost:8080/product/v1/checkout \
  -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
  -d '{"name":"Áo","amount":150000,"quantity":1,"currency":"vnd","orderId":"ORD-1"}'
```
Lần 2 trả `"duplicate": true` và **cùng `sessionId`**. Kiểm tra DB chỉ có 1 dòng:
```bash
docker exec -it stripe-payment-postgres \
  psql -U stripe -d stripe_payment -c "SELECT order_ref, status, stripe_session_id FROM orders;"
```

Trên **giao diện web**: key hiển thị dưới form; bấm nút thanh toán 2 lần (không đổi
key) → khung kết quả báo "Request trùng".

---

## 6. Tra cứu đơn + kết quả sau thanh toán

Sau khi thanh toán bằng thẻ test, webhook đổi `status` sang `PAID`. Tra cứu:
```bash
curl http://localhost:8080/orders/ORD-1
```
```json
{
  "orderRef": "ORD-1",
  "status": "PAID",
  "mode": "payment",
  "amountTotal": 150000,
  "customerEmail": "...",
  "stripePaymentIntentId": "pi_...",
  "createdAt": "...", "updatedAt": "..."
}
```
`stripePaymentIntentId` chính là thứ dùng để gọi `/refund`. Sau khi hoàn tiền,
webhook `charge.refunded` sẽ đổi `status` sang `REFUNDED`.
