# Hướng dẫn tích hợp Stripe Payment với Spring Boot

Tài liệu này giải thích chi tiết cách demo hoạt động: các khái niệm Stripe, kiến
trúc dự án, luồng chạy end-to-end (bao gồm webhook), giải thích từng file, và cách
chạy/test.

---

## 1. Tổng quan

Demo là một ứng dụng Spring Boot cho phép người dùng:

1. Nhập thông tin sản phẩm (tên, giá, số lượng, tiền tệ) trên một form web.
2. Bấm "Thanh toán" → backend tạo một **Stripe Checkout Session** và trả về URL
   trang thanh toán do **Stripe host** (không phải trang của chúng ta).
3. Người dùng nhập thẻ trên trang Stripe, trả tiền xong sẽ được redirect về
   `/success` (hoặc `/cancel` nếu hủy).
4. Song song đó, **server của Stripe** gọi webhook `POST /webhook` của chúng ta để
   xác nhận chắc chắn giao dịch đã hoàn tất — đây mới là nguồn tin cậy để cập nhật
   đơn hàng.

Công nghệ: Spring Boot 4.1.0, Java 21, Thymeleaf (giao diện), thư viện
`stripe-java` 28.4.0.

---

## 2. Các khái niệm Stripe cần nắm

### 2.1. Stripe Checkout là gì
Là trang thanh toán **dựng sẵn, do Stripe host và bảo trì**. Ta không tự làm form
nhập số thẻ (điều đó kéo theo trách nhiệm tuân thủ chuẩn bảo mật PCI). Ta chỉ tạo
một "phiên" (Session) mô tả cần thu bao nhiêu tiền, rồi đẩy người dùng sang trang
đó. Số thẻ không bao giờ đi qua server của chúng ta.

### 2.2. Checkout Session
Đại diện cho **một phiên thanh toán**. Khi tạo Session, Stripe trả về:
- `id` (vd `cs_test_...`): định danh phiên.
- `url`: địa chỉ trang thanh toán để redirect người dùng tới.
- `status`: trạng thái phiên (`open`, `complete`, `expired`).

### 2.3. Line Item, Price Data, Product Data
Cấu trúc phân cấp mô tả "đang bán cái gì, giá bao nhiêu":

```
Session
 └─ LineItem (1 dòng hàng)
     ├─ quantity (số lượng)
     └─ PriceData
         ├─ currency (tiền tệ, vd "usd", "vnd")
         ├─ unitAmount (đơn giá — xem 2.5)
         └─ ProductData
             └─ name (tên sản phẩm hiển thị)
```

Ở đây ta tạo giá "inline" (ad-hoc) ngay lúc checkout. Trong hệ thống thật, bạn có
thể tạo sẵn Product/Price trong dashboard Stripe rồi chỉ tham chiếu bằng `priceId`.

### 2.4. Mode = PAYMENT
Checkout có nhiều chế độ:
- `PAYMENT`: thu tiền **một lần** (demo dùng cái này).
- `SUBSCRIPTION`: thu định kỳ (gói thuê bao).
- `SETUP`: chỉ lưu thẻ để dùng sau, không thu tiền ngay.

### 2.5. Đơn vị tiền nhỏ nhất (rất dễ sai!)
`unitAmount` tính bằng **đơn vị nhỏ nhất của tiền tệ**, không phải đơn vị chính:
- USD: `1000` = **$10.00** (1000 cents).
- EUR: `500` = **€5.00**.
- VND, JPY: là tiền tệ **không có phần lẻ (zero-decimal)** → `150000` = **150.000₫**.

Nếu để `usd` với amount `10` bạn sẽ chỉ thu **$0.10**. Luôn kiểm tra kỹ đơn vị.

### 2.6. Publishable key vs Secret key
Stripe cấp cặp khóa:
- **Publishable key** (`pk_test_...`): dùng ở **phía client** (trình duyệt). Công
  khai được. Demo này không cần vì trang thanh toán do Stripe host.
- **Secret key** (`sk_test_...`): dùng ở **phía server**. **BÍ MẬT** — ai có nó
  có thể thao tác tài khoản Stripe của bạn. Không bao giờ commit vào git, không để
  lộ ra client.

Tiền tố `test` = môi trường thử nghiệm (không có tiền thật). Khi lên production sẽ
là `pk_live_` / `sk_live_`.

### 2.7. Webhook Secret
Một khóa riêng (`whsec_...`) dùng để **xác thực** rằng request webhook đúng là do
Stripe gửi, không phải kẻ giả mạo. Xem mục 5.

---

## 3. Kiến trúc dự án

```
com.example.stripe_payment
├─ StripePaymentApplication        # điểm khởi động Spring Boot
├─ controller
│   ├─ WebController               # GET /, /success, /cancel  → trả trang HTML
│   ├─ ProductCheckoutController   # POST /product/v1/checkout  → tạo Session (REST/JSON)
│   └─ StripeWebhookController     # POST /webhook              → nhận sự kiện từ Stripe
├─ service
│   └─ StripeService              # logic gọi Stripe SDK để tạo Checkout Session
└─ dto
    ├─ ProductRequest             # dữ liệu vào: name, amount, quantity, currency
    └─ StripeResponse             # dữ liệu ra: status, message, sessionId, sessionUrl

src/main/resources
├─ application.yaml               # cấu hình: secret key, webhook secret, base URL
└─ templates/                     # Thymeleaf: index.html, success.html, cancel.html
```

Đây là kiến trúc phân lớp chuẩn của Spring MVC: **Controller** (nhận request) →
**Service** (logic nghiệp vụ) → **DTO** (đối tượng truyền dữ liệu).

---

## 4. Luồng chạy end-to-end

```
┌──────────┐   (1) GET /            ┌─────────────────┐
│ Trình     │ ─────────────────────▶ │  WebController   │  trả về form index.html
│ duyệt     │ ◀───────────────────── │                  │
│ (User)    │                        └─────────────────┘
│           │
│           │   (2) POST /product/v1/checkout  {name, amount, quantity, currency}
│           │ ─────────────────────▶ ┌───────────────────────────┐
│           │                        │ ProductCheckoutController  │
│           │                        │        │                   │
│           │                        │        ▼                   │
│           │                        │   StripeService            │
│           │                        │   Session.create(...)  ────┼──▶  Stripe API
│           │                        │                            │ ◀── trả về Session
│           │   (3) {sessionUrl}     │                            │     (id, url)
│           │ ◀───────────────────── └───────────────────────────┘
│           │
│           │   (4) window.location = sessionUrl
│           │ ─────────────────────────────────────────────▶  Trang thanh toán
│           │                                                   do Stripe host
│           │   (5) Nhập thẻ 4242... và trả tiền
│           │
│           │   (6a) Redirect trình duyệt về /success  (chỉ để HIỂN THỊ)
│           │ ◀─────────────────────────────────────────────
└──────────┘

        (6b) SONG SONG: Stripe SERVER ──POST /webhook──▶ StripeWebhookController
                          (checkout.session.completed)     → verify chữ ký
                                                            → cập nhật đơn hàng
                          ĐÂY MỚI LÀ NGUỒN SỰ THẬT ✅
```

**Điểm mấu chốt**: bước (6a) và (6b) là hai kênh khác nhau:
- (6a) chạy ở **trình duyệt người dùng** → không đáng tin (user có thể đóng tab,
  hoặc tự gõ URL `/success`).
- (6b) chạy **server-to-server** → đáng tin, dùng để cập nhật dữ liệu thật.

---

## 5. Webhook — vì sao và như thế nào

### 5.1. Vì sao redirect `/success` không đủ
- User trả tiền xong nhưng **đóng tab ngay** → không bao giờ tới `/success`, dù
  tiền đã trừ. Bạn mất đơn.
- User (hoặc kẻ xấu) **tự gõ tay** `http://localhost:8080/success` mà không trả
  đồng nào → bạn tưởng đã thu tiền.
- Mạng rớt lúc redirect → mất thông tin.

⇒ `/success` chỉ để hiển thị lời cảm ơn. **Không** dùng nó để cộng số dư/giao hàng.

### 5.2. Webhook hoạt động ra sao
Stripe **chủ động gọi** `POST /webhook` tới server của bạn mỗi khi có sự kiện,
độc lập với trình duyệt. Sự kiện quan trọng nhất với Checkout là
**`checkout.session.completed`** (phiên thanh toán đã hoàn tất).

### 5.3. Xác thực chữ ký (bắt buộc)
`/webhook` là URL công khai → ai cũng gửi POST giả được. Stripe ký mỗi request
bằng Webhook Secret và gắn vào header `Stripe-Signature`. Backend verify:

```java
Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
```

Sai chữ ký → ném exception → ta trả `400`. Chỉ khi khớp mới tin.

⚠️ **Phải verify trên RAW body** (chuỗi gốc). Vì thế controller nhận
`@RequestBody String payload` chứ không để Spring parse thành object rồi
serialize lại (sẽ làm lệch chữ ký).

### 5.4. Trả về 200
Sau khi xử lý xong, trả HTTP `200` để Stripe biết đã nhận. Nếu không nhận được
`2xx`, Stripe sẽ **gửi lại** sự kiện nhiều lần (retry). ⇒ Nên xử lý idempotent
(cùng một sự kiện tới 2 lần không được cộng tiền 2 lần) trong hệ thống thật.

---

## 6. Giải thích từng file code

### `dto/ProductRequest.java`
DTO dữ liệu vào. Lombok `@Data` sinh getter/setter, `@AllArgsConstructor` +
`@NoArgsConstructor` sinh constructor. Các trường: `amount` (Long, đơn vị nhỏ
nhất), `quantity` (Long), `name` (String), `currency` (String).

### `dto/StripeResponse.java`
DTO dữ liệu ra. Có thêm `@Builder` để dựng object theo kiểu chuỗi `.status(...)
.message(...)`. Trường: `status`, `message`, `sessionId`, `sessionUrl`.

### `service/StripeService.java`
Nơi chứa toàn bộ logic Stripe:
- `@PostConstruct init()`: gán `Stripe.apiKey = secretKey` **một lần** khi bean
  khởi tạo (`Stripe.apiKey` là biến static toàn cục của SDK).
- `checkoutProduct(...)`: dựng `ProductData → PriceData → LineItem →
  SessionCreateParams` (mode PAYMENT, success/cancel URL lấy từ `app.base-url`),
  gọi `Session.create(params)`, rồi map sang `StripeResponse`.
- `StripeException` được bắt và ném lại thành `RuntimeException`.

### `controller/ProductCheckoutController.java`
REST controller. `POST /product/v1/checkout` nhận `ProductRequest` (JSON body),
gọi service, trả `StripeResponse`. Dùng `@RequiredArgsConstructor` để inject
`StripeService` qua constructor.

### `controller/WebController.java`
`@Controller` (không phải `@RestController`) phục vụ 3 route GET trả về tên
template Thymeleaf: `/` → `index`, `/success` → `success`, `/cancel` → `cancel`.

### `controller/StripeWebhookController.java`
`POST /webhook`: nhận raw body + header `Stripe-Signature`, verify bằng
`Webhook.constructEvent`, lọc sự kiện `checkout.session.completed` và log thông
tin đơn. Chỗ `TODO` là nơi trong thực tế bạn cập nhật DB/gửi email/giao hàng.

### `templates/index.html`
Form nhập sản phẩm + JavaScript `fetch('/product/v1/checkout')` rồi
`window.location.href = data.sessionUrl` để chuyển sang trang Stripe.

### `templates/success.html` / `cancel.html`
Trang tĩnh hiển thị kết quả.

### `application.yaml`
```yaml
stripe:
  secretKey: ${STRIPE_SECRET_KEY}       # đọc từ biến môi trường
  webhookSecret: ${STRIPE_WEBHOOK_SECRET}
app:
  base-url: ${APP_BASE_URL:http://localhost:8080}   # có giá trị mặc định
```
Không hardcode secret. Cú pháp `${TÊN:mặc_định}` cho phép đặt giá trị dự phòng.

---

## 7. Cách chạy demo

### 7.1. Lấy khóa Stripe
1. Đăng ký tài khoản tại https://dashboard.stripe.com (miễn phí).
2. Bật chế độ **Test mode**, vào **Developers → API keys**, copy **Secret key**
   (`sk_test_...`).

### 7.2. Đặt biến môi trường
```bash
export STRIPE_SECRET_KEY=sk_test_xxxxxxxxxxxxxxxxx
export STRIPE_WEBHOOK_SECRET=whsec_xxxxxxxxxxxxx   # lấy ở bước 7.4
```

### 7.3. Chạy ứng dụng
```bash
./mvnw spring-boot:run
```
Mở trình duyệt: http://localhost:8080 → điền form → bấm **Thanh toán**.

Trên trang Stripe, dùng **thẻ test**:
- Số thẻ: `4242 4242 4242 4242`
- Ngày hết hạn: bất kỳ ngày tương lai (vd `12/34`)
- CVC: bất kỳ 3 số (vd `123`)
- Các trường khác: điền tùy ý

Trả tiền xong → được redirect về `/success`.
(Thẻ test khác: `4000 0000 0000 9995` để mô phỏng thẻ bị từ chối.)

### 7.4. Test webhook ở local bằng Stripe CLI
`localhost` không có địa chỉ public để Stripe gọi vào, nên dùng Stripe CLI forward:

```bash
# Cài đặt (macOS)
brew install stripe/stripe-cli/stripe

stripe login                                      # xác thực CLI với tài khoản
stripe listen --forward-to localhost:8080/webhook # forward sự kiện về local
```
Lệnh `stripe listen` in ra một dòng `Ready! ... webhook signing secret is
whsec_...` → copy giá trị `whsec_...` này vào `STRIPE_WEBHOOK_SECRET` (mục 7.2)
rồi khởi động lại app.

Giờ mỗi lần bạn thanh toán thành công qua UI, terminal chạy `stripe listen` sẽ
hiện sự kiện được forward, và log của app sẽ in
`✅ Đơn cs_test_... đã thanh toán`.

Bắn thử một sự kiện thủ công (không cần thanh toán qua UI):
```bash
stripe trigger checkout.session.completed
```

---

## 8. Test bằng curl / Postman (chỉ phần tạo Session)

```bash
curl -X POST http://localhost:8080/product/v1/checkout \
  -H "Content-Type: application/json" \
  -d '{"name":"Áo thun demo","amount":150000,"quantity":1,"currency":"vnd"}'
```
Kết quả:
```json
{
  "status": "open",
  "message": "Success",
  "sessionId": "cs_test_...",
  "sessionUrl": "https://checkout.stripe.com/c/pay/cs_test_..."
}
```
Mở `sessionUrl` trên trình duyệt để tới trang thanh toán.

---

## 9. Các bước tiếp theo (ngoài phạm vi demo)

- **Idempotency cho webhook**: lưu `event.id` đã xử lý để tránh cộng tiền 2 lần
  khi Stripe retry.
- **Lưu đơn hàng vào DB**: tạo bản ghi "pending" khi tạo Session, cập nhật
  "paid" khi webhook báo hoàn tất.
- **Trang thất bại/từ chối**: xử lý `payment_intent.payment_failed`.
- **Chuyển sang live keys** khi lên production và cấu hình webhook endpoint thật
  trong Stripe Dashboard (thay cho Stripe CLI).

---

## 10. Bảo mật — nhắc lại

- **Không commit** `sk_...` hay `whsec_...` vào git. Dùng biến môi trường / secret
  manager.
- Nếu secret key từng bị lộ (vd đã commit trước đây), hãy **roll (thu hồi + tạo
  mới)** key đó trong Stripe Dashboard.
- Luôn **verify chữ ký webhook** — không bao giờ tin request `/webhook` chưa xác thực.
