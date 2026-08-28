# Data Types & Table Design trong PostgreSQL — Chi tiết đầy đủ

> Domain xuyên suốt ví dụ: hệ thống bán hàng (`customers`, `products`, `orders`, `order_items`).
> Mức độ bài tập: 🟢 Cơ bản — 🟡 Trung bình — 🔴 Nâng cao — ⚫ Chuyên sâu/thực chiến.

---

## 1. Nhóm kiểu số (Numeric)

| Kiểu | Kích thước | Khoảng giá trị | Khi nào dùng |
|---|---|---|---|
| `smallint` | 2 byte | -32,768 → 32,767 | Số nhỏ, ít dùng (vd rank, level) |
| `integer` (`int`, `int4`) | 4 byte | ±2.1 tỷ | Mặc định cho id, số lượng |
| `bigint` (`int8`) | 8 byte | ±9.2 × 10^18 | ID có khả năng vượt 2 tỷ dòng, timestamp dạng epoch |
| `numeric(p,s)` / `decimal(p,s)` | biến đổi | chính xác tuyệt đối | **Tiền tệ, giá cả** — luôn dùng cho money |
| `real` (`float4`) | 4 byte | ~6 chữ số thập phân, có sai số | Khoa học, không dùng cho tiền |
| `double precision` (`float8`) | 8 byte | ~15 chữ số thập phân, có sai số | Tính toán khoa học |
| `smallserial`/`serial`/`bigserial` | tương ứng int2/int4/int8 | tự tăng | **Cũ**, nên thay bằng `GENERATED ... AS IDENTITY` |

### Ví dụ

```sql
CREATE TABLE products (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL,
    price       numeric(12,2) NOT NULL CHECK (price >= 0),
    stock       integer NOT NULL DEFAULT 0 CHECK (stock >= 0),
    rating      real  -- 4.5 sao, sai số nhỏ không quan trọng
);
```

⚠️ **Vì sao KHÔNG dùng `real`/`double precision` cho tiền**: đây là kiểu dấu phẩy động (floating point), có sai số làm tròn nhị phân — `0.1 + 0.2` có thể ra `0.30000000000000004`. Với tiền tệ, luôn dùng `numeric(p,s)` — kiểu số chính xác tuyệt đối (arbitrary precision), đánh đổi lại performance chậm hơn 1 chút nhưng an toàn tuyệt đối cho tính toán tài chính.

### `numeric(p,s)` — ý nghĩa p (precision) và s (scale)

```sql
price numeric(10,2)
-- p=10: tổng số chữ số tối đa (cả trước và sau dấu phẩy)
-- s=2:  số chữ số sau dấu phẩy
-- → giá trị hợp lệ tối đa: 99999999.99 (8 chữ số nguyên + 2 chữ số thập phân)
```

---

## 2. Nhóm kiểu chuỗi (Text/Character)

| Kiểu | Đặc điểm |
|---|---|
| `char(n)` | Độ dài **cố định**, tự động pad khoảng trắng nếu ngắn hơn n — hiếm dùng, dễ gây bug so sánh chuỗi |
| `varchar(n)` | Độ dài **tối đa** n, không pad | Dùng khi thực sự cần giới hạn cứng (vd mã bưu điện, mã quốc gia) |
| `text` | Không giới hạn độ dài | **Mặc định nên dùng** cho hầu hết trường hợp |

### Điều cần nhớ

PostgreSQL **không phạt hiệu năng** khi dùng `text` so với `varchar(n)` — khác với MySQL (nơi `varchar` có tối ưu riêng). Nội bộ Postgres lưu cả 2 kiểu giống hệt nhau (`varlena`), `varchar(n)` chỉ thêm bước kiểm tra độ dài. Vì vậy khuyến nghị: **dùng `text` mặc định**, chỉ dùng `varchar(n)` khi giới hạn độ dài là **yêu cầu nghiệp vụ thật sự** (không phải để "tối ưu hiệu năng").

```sql
CREATE TABLE customers (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name   text NOT NULL,
    email       text UNIQUE NOT NULL,
    country_code char(2)      -- mã quốc gia luôn đúng 2 ký tự: 'VN', 'US'
);
```

---

## 3. Nhóm kiểu thời gian (Date/Time)

| Kiểu | Lưu gì | Lưu ý |
|---|---|---|
| `date` | Chỉ ngày, không giờ | Ngày sinh, ngày hết hạn |
| `time` | Chỉ giờ, không ngày | Giờ mở cửa hàng ngày |
| `timestamp` (`timestamp without time zone`) | Ngày + giờ, **không** biết timezone | ⚠️ Dễ gây bug khi hệ thống chạy nhiều timezone |
| `timestamptz` (`timestamp with time zone`) | Ngày + giờ, **luôn lưu quy đổi về UTC** | ✅ **Nên dùng mặc định** |
| `interval` | Khoảng thời gian (vd `'3 days'`, `'2 hours'`) | Tính toán chênh lệch thời gian |

### Vì sao luôn ưu tiên `timestamptz`

```sql
-- timestamp: lưu đúng y chang chuỗi nhập vào, KHÔNG biết đó là giờ ở đâu
-- timestamptz: PostgreSQL tự quy đổi về UTC khi lưu, tự quy đổi về timezone
--              của client khi đọc ra (dựa theo session setting "TimeZone")

CREATE TABLE orders (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at  timestamptz NOT NULL DEFAULT now()  -- ✅ luôn dùng timestamptz
);

SET TIME ZONE 'Asia/Ho_Chi_Minh';
SELECT created_at FROM orders WHERE id = 1;  -- hiển thị theo giờ VN

SET TIME ZONE 'UTC';
SELECT created_at FROM orders WHERE id = 1;  -- cùng 1 giá trị, hiển thị theo UTC
-- Dữ liệu vật lý lưu trong DB không đổi — chỉ cách HIỂN THỊ thay đổi theo session
```

Với `timestamp` (không có tz), nếu server chuyển timezone hoặc ứng dụng chạy ở nhiều region khác nhau, dữ liệu **có thể bị hiểu sai** vì không biết giờ đó thuộc múi giờ nào.

### `interval` — ví dụ thực tế

```sql
SELECT now() - created_at AS thoi_gian_da_qua FROM orders WHERE id = 1;
-- kết quả kiểu interval, vd: "3 days 04:12:33"

SELECT * FROM orders WHERE created_at < now() - interval '7 days';  -- đơn hàng cũ hơn 7 ngày
```

---

## 4. Nhóm kiểu khác

### `boolean`

```sql
is_active boolean NOT NULL DEFAULT true
```

Nhận `true/false`, và các alias `'t'/'f'`, `'yes'/'no'`, `'1'/'0'` khi insert (tự convert).

### `uuid`

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- hoặc "uuid-ossp"

CREATE TABLE api_keys (
    id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    key_value text NOT NULL
);
```

Dùng khi cần ID **không đoán được** (bảo mật hơn serial tăng dần), hoặc khi cần sinh ID ở tầng application trước khi ghi DB (tránh round-trip lấy id).

### `bytea` — dữ liệu nhị phân

```sql
avatar bytea  -- lưu ảnh nhỏ trực tiếp trong DB (thường KHÔNG khuyến khích cho file lớn, nên lưu path/S3 URL)
```

### `json` vs `jsonb`

| | `json` | `jsonb` |
|---|---|---|
| Lưu trữ | Text thô, giữ nguyên format/thứ tự key | Binary, đã parse sẵn |
| Tốc độ ghi | Nhanh hơn (không parse) | Chậm hơn 1 chút (phải parse lúc insert) |
| Tốc độ đọc/query | Chậm hơn (phải parse mỗi lần đọc) | **Nhanh hơn nhiều** |
| Index hỗ trợ | Không | **GIN index** — query nhanh |
| Trùng key | Giữ tất cả bản trùng | Chỉ giữ giá trị cuối cùng |

```sql
CREATE TABLE products (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL,
    attributes  jsonb  -- {"color": "red", "size": "L"} — thuộc tính động, không cố định schema
);

CREATE INDEX idx_products_attributes ON products USING GIN (attributes);

SELECT * FROM products WHERE attributes @> '{"color": "red"}';  -- tận dụng GIN index
```

→ **Khuyến nghị**: gần như luôn dùng `jsonb`, chỉ dùng `json` khi thực sự cần giữ nguyên format/thứ tự văn bản gốc (hiếm gặp).

### Array

```sql
CREATE TABLE products (
    id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name  text NOT NULL,
    tags  text[]  -- ['electronics', 'sale', 'featured']
);

INSERT INTO products (name, tags) VALUES ('Laptop', ARRAY['electronics', 'sale']);

SELECT * FROM products WHERE 'sale' = ANY(tags);
SELECT * FROM products WHERE tags @> ARRAY['electronics'];  -- chứa tag này

CREATE INDEX idx_products_tags ON products USING GIN (tags);  -- tăng tốc query trên array
```

⚠️ Array tiện cho dữ liệu đơn giản (danh sách tag, danh sách số điện thoại), nhưng nếu cần **quan hệ phức tạp hơn** (mỗi tag có thêm thuộc tính, cần join ngược từ tag → product), nên tách thành bảng riêng (`product_tags`) theo chuẩn quan hệ — array không thay thế được foreign key/join thật sự.

### `ENUM` — kiểu liệt kê cố định

```sql
CREATE TYPE order_status AS ENUM ('pending', 'paid', 'shipped', 'cancelled');

CREATE TABLE orders (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status  order_status NOT NULL DEFAULT 'pending'
);

-- Thêm giá trị mới vào ENUM sau này:
ALTER TYPE order_status ADD VALUE 'refunded' AFTER 'shipped';
```

⚠️ **Nhược điểm của ENUM**: khó thay đổi (không xóa được giá trị, không đổi thứ tự dễ dàng), và giá trị ENUM gắn chặt với type ở tầng DB — khi có nhiều service/ngôn ngữ khác nhau cùng truy cập DB, đôi khi dùng bảng lookup (`status_id` FK tới bảng `order_statuses`) linh hoạt hơn ENUM, dù tốn thêm 1 JOIN.

### `inet` / `cidr` — địa chỉ mạng

```sql
CREATE TABLE login_logs (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     bigint,
    ip_address  inet,
    logged_in_at timestamptz DEFAULT now()
);

INSERT INTO login_logs (user_id, ip_address) VALUES (1, '203.113.1.50');
SELECT * FROM login_logs WHERE ip_address << inet '203.113.0.0/16';  -- IP nằm trong subnet
```

### `money` — nên tránh

Kiểu `money` tồn tại nhưng **không khuyến khích dùng** vì phụ thuộc vào `lc_monetary` locale setting của session (dễ gây lỗi khi đổi locale), và giới hạn phép toán. Luôn ưu tiên `numeric(p,s)` cho tiền tệ.

---

## 5. Domain — tái sử dụng constraint

`DOMAIN` cho phép định nghĩa 1 kiểu dữ liệu tùy chỉnh dựa trên kiểu có sẵn, kèm ràng buộc — dùng nhiều nơi mà không phải lặp lại `CHECK` ở từng bảng.

```sql
CREATE DOMAIN email_type AS text
    CHECK (VALUE ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$');

CREATE DOMAIN positive_numeric AS numeric
    CHECK (VALUE > 0);

CREATE TABLE customers (
    id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email  email_type NOT NULL
);

CREATE TABLE products (
    id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    price  positive_numeric NOT NULL
);
```

Thay đổi ràng buộc ở 1 nơi (`ALTER DOMAIN`) sẽ áp dụng cho **mọi cột** đang dùng domain đó — tiện hơn nhiều so với sửa từng `CHECK` rải rác nhiều bảng.

```sql
ALTER DOMAIN email_type ADD CONSTRAINT email_max_length CHECK (length(VALUE) <= 254);
```

---

## 6. Range types — kiểu khoảng giá trị

```sql
-- int4range, int8range, numrange, tsrange, tstzrange, daterange
CREATE TABLE room_bookings (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id  int NOT NULL,
    during   tstzrange NOT NULL  -- khoảng thời gian đặt phòng
);

INSERT INTO room_bookings (room_id, during)
VALUES (1, tstzrange('2026-09-01 09:00', '2026-09-01 11:00'));

SELECT * FROM room_bookings WHERE during @> '2026-09-01 10:00'::timestamptz;  -- thời điểm nằm trong khoảng
SELECT * FROM room_bookings WHERE during && tstzrange('2026-09-01 10:30', '2026-09-01 12:00');  -- có giao nhau
```

---

## 7. Constraints — ràng buộc toàn vẹn dữ liệu

### `PRIMARY KEY`

```sql
id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY
-- tương đương: NOT NULL + UNIQUE + có index tự động
```

Composite primary key (khóa chính nhiều cột):

```sql
CREATE TABLE order_items (
    order_id    bigint REFERENCES orders(id),
    product_id  bigint REFERENCES products(id),
    qty         integer NOT NULL CHECK (qty > 0),
    PRIMARY KEY (order_id, product_id)  -- 1 sản phẩm chỉ xuất hiện 1 lần trong 1 đơn
);
```

### `FOREIGN KEY` — với các tùy chọn `ON DELETE`/`ON UPDATE`

```sql
CREATE TABLE orders (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  bigint NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    created_at   timestamptz DEFAULT now()
);

CREATE TABLE order_items (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    bigint NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  bigint NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    qty         integer NOT NULL CHECK (qty > 0)
);
```

| Tùy chọn `ON DELETE` | Hành vi khi dòng cha bị xóa |
|---|---|
| `RESTRICT` (mặc định gần giống) | Chặn xóa nếu còn dòng con tham chiếu |
| `CASCADE` | Tự động xóa luôn các dòng con liên quan |
| `SET NULL` | Đặt cột FK ở dòng con về `NULL` |
| `SET DEFAULT` | Đặt cột FK về giá trị `DEFAULT` |
| `NO ACTION` (mặc định thật sự nếu không ghi gì) | Giống RESTRICT nhưng kiểm tra trễ hơn (cuối transaction) |

→ Trong ví dụ trên: xóa 1 `order` sẽ tự xóa toàn bộ `order_items` liên quan (`CASCADE` hợp lý vì order_items "sống phụ thuộc" order); nhưng xóa 1 `product` đang được tham chiếu trong đơn hàng sẽ **bị chặn** (`RESTRICT`) — tránh mất dấu vết lịch sử bán hàng.

### `UNIQUE`

```sql
email text UNIQUE NOT NULL

-- Unique nhiều cột:
CREATE TABLE product_variants (
    product_id  bigint REFERENCES products(id),
    sku         text,
    UNIQUE (product_id, sku)
);
```

### `CHECK`

```sql
CREATE TABLE products (
    id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    price  numeric(12,2) NOT NULL CHECK (price >= 0),
    discount_price numeric(12,2) CHECK (discount_price IS NULL OR discount_price < price)
);
```

`CHECK` chạy ở mức row — có thể tham chiếu nhiều cột trong cùng 1 dòng, nhưng **không** tham chiếu được dữ liệu ở dòng khác hay bảng khác (muốn vậy phải dùng `TRIGGER`).

### `NOT NULL`

```sql
full_name text NOT NULL
```

Mặc định mọi cột **cho phép NULL** trừ khi khai báo `NOT NULL` tường minh, hoặc là `PRIMARY KEY`.

### `EXCLUDE` — chặn overlap dữ liệu

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE room_bookings (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id  int NOT NULL,
    during   tstzrange NOT NULL,
    EXCLUDE USING gist (room_id WITH =, during WITH &&)
    -- không cho phép 2 booking cùng room_id có "during" giao nhau
);

INSERT INTO room_bookings (room_id, during) VALUES (1, tstzrange('2026-09-01 09:00', '2026-09-01 11:00'));
INSERT INTO room_bookings (room_id, during) VALUES (1, tstzrange('2026-09-01 10:00', '2026-09-01 12:00'));
-- ❌ ERROR: conflicting key value violates exclusion constraint
```

`EXCLUDE` là dạng tổng quát hóa của `UNIQUE` — thay vì chỉ kiểm tra "bằng nhau", nó kiểm tra bất kỳ toán tử nào (`=`, `&&`, `<>`...) giữa các dòng.

---

## 8. Generated Column — cột tính toán tự động

```sql
CREATE TABLE order_items (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     bigint NOT NULL REFERENCES orders(id),
    qty          integer NOT NULL,
    unit_price   numeric(12,2) NOT NULL,
    total_amount numeric(14,2) GENERATED ALWAYS AS (qty * unit_price) STORED
);

INSERT INTO order_items (order_id, qty, unit_price) VALUES (1, 3, 50000);
SELECT total_amount FROM order_items WHERE order_id = 1;  -- tự động = 150000, không cần insert
```

- `STORED`: giá trị được **tính và lưu vật lý** trên đĩa, tự cập nhật lại khi cột nguồn thay đổi (hiện tại Postgres chỉ hỗ trợ `STORED`, chưa hỗ trợ `VIRTUAL`).
- Không thể `INSERT`/`UPDATE` trực tiếp vào generated column — Postgres tự tính.
- Hữu ích để tránh sai lệch logic tính toán bị lặp lại nhiều nơi trong application.

---

## 9. `GENERATED ... AS IDENTITY` vs `SERIAL` — nên dùng cái nào

```sql
-- Cách cũ (vẫn hoạt động, nhưng không còn khuyến khích)
CREATE TABLE t_old (id serial PRIMARY KEY);

-- Cách chuẩn SQL, khuyến nghị dùng từ PG10 trở đi
CREATE TABLE t_new (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY);
```

| | `serial` | `GENERATED AS IDENTITY` |
|---|---|---|
| Chuẩn SQL | ❌ Đặc thù Postgres | ✅ Chuẩn SQL:2003 |
| Cơ chế | Tạo sequence + set DEFAULT ngầm, tách rời khỏi cột | Gắn chặt với cột, Postgres quản lý vòng đời sequence chặt hơn |
| Cho phép INSERT tay đè giá trị | Luôn cho phép (dễ gây trùng lặp nếu không cẩn thận) | `GENERATED ALWAYS` mặc định **chặn** insert tay (trừ khi dùng `OVERRIDING SYSTEM VALUE`) |
| Linh hoạt hơn khi cần insert tay | — | Dùng `GENERATED BY DEFAULT AS IDENTITY` nếu muốn cho phép ghi đè như serial |

```sql
CREATE TABLE t (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY);

INSERT INTO t (id) VALUES (100);
-- ❌ ERROR: cannot insert into column "id" (GENERATED ALWAYS chặn insert tay)

INSERT INTO t OVERRIDING SYSTEM VALUE (id) VALUES (100);
-- ✅ Chạy được nếu thực sự cần (migration dữ liệu cũ chẳng hạn)
```

---

## 10. Thiết kế bảng thực tế — ví dụ tổng hợp domain bán hàng

```sql
CREATE TYPE order_status AS ENUM ('pending', 'paid', 'shipped', 'cancelled');

CREATE DOMAIN email_type AS text CHECK (VALUE ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$');

CREATE TABLE customers (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name   text NOT NULL,
    email       email_type UNIQUE NOT NULL,
    tags        text[] DEFAULT '{}',
    metadata    jsonb DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku         text UNIQUE NOT NULL,
    name        text NOT NULL,
    price       numeric(12,2) NOT NULL CHECK (price >= 0),
    stock       integer NOT NULL DEFAULT 0 CHECK (stock >= 0),
    attributes  jsonb DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id bigint NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    status      order_status NOT NULL DEFAULT 'pending',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      bigint NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id    bigint NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    qty           integer NOT NULL CHECK (qty > 0),
    unit_price    numeric(12,2) NOT NULL CHECK (unit_price >= 0),
    total_amount  numeric(14,2) GENERATED ALWAYS AS (qty * unit_price) STORED,
    UNIQUE (order_id, product_id)
);
```

---

## 11. Bảng quyết định nhanh: chọn kiểu dữ liệu

| Nhu cầu | Chọn |
|---|---|
| ID tự tăng | `bigint GENERATED ALWAYS AS IDENTITY` |
| ID không đoán được (bảo mật) | `uuid DEFAULT gen_random_uuid()` |
| Tiền tệ, số lượng cần chính xác tuyệt đối | `numeric(p,s)` |
| Chuỗi văn bản (hầu hết trường hợp) | `text` |
| Chuỗi có giới hạn cứng theo nghiệp vụ | `varchar(n)` |
| Mã cố định độ dài (mã quốc gia, mã ISO) | `char(n)` |
| Thời gian có ý nghĩa toàn cầu | `timestamptz` |
| Chỉ ngày (sinh nhật, hạn dùng) | `date` |
| Dữ liệu có cấu trúc động, không cố định schema | `jsonb` |
| Danh sách giá trị đơn giản, không cần join | `array` |
| Danh sách giá trị có quan hệ phức tạp | Bảng riêng (chuẩn hóa) |
| Trạng thái cố định, ít thay đổi | `ENUM` |
| Trạng thái hay thay đổi/thêm mới, đa ngôn ngữ | Bảng lookup (FK) |
| Khoảng thời gian/số, cần kiểm tra overlap | Range type + `EXCLUDE` |
| Ràng buộc dùng lại nhiều bảng | `DOMAIN` |

---

## 12. Lỗi thiết kế thường gặp

| Lỗi | Hậu quả | Cách tránh |
|---|---|---|
| Dùng `timestamp` thay vì `timestamptz` | Sai lệch giờ khi hệ thống đa timezone | Luôn dùng `timestamptz` |
| Dùng `float`/`double` cho tiền | Sai số làm tròn, sai lệch số liệu tài chính | Dùng `numeric(p,s)` |
| Dùng `varchar(n)` khắp nơi "cho chắc" | Giới hạn cứng nhắc không cần thiết, phải `ALTER TABLE` khi đổi yêu cầu | Dùng `text`, giới hạn ở tầng validate application nếu cần |
| Quên `NOT NULL` cho cột bắt buộc | Dữ liệu rác `NULL` lọt vào, logic nghiệp vụ sai | Rà soát kỹ cột nào thực sự optional |
| Dùng `ON DELETE CASCADE` tùy tiện | Xóa nhầm 1 dòng kéo theo mất dữ liệu lịch sử quan trọng | Cân nhắc kỹ giữa `CASCADE`/`RESTRICT`/`SET NULL` theo từng quan hệ |
| Nhồi quá nhiều dữ liệu không cấu trúc vào `jsonb` thay vì chuẩn hóa | Khó query, khó ràng buộc toàn vẹn, khó tối ưu index | Chỉ dùng `jsonb` cho phần dữ liệu thực sự động/không cố định |
| Dùng `serial` cho hệ thống mới | Không theo chuẩn SQL, cho phép insert tay gây trùng dễ dàng hơn | Dùng `GENERATED ALWAYS AS IDENTITY` |

---

## 13. Bài tập thực hành (tăng dần độ khó)

- 🟢 Tạo bảng `customers(id, full_name, email UNIQUE, created_at timestamptz DEFAULT now())` dùng `GENERATED ALWAYS AS IDENTITY`.
- 🟢 Tạo bảng `products(id, sku, name, price numeric(12,2) CHECK (price >= 0), tags text[])`.
- 🟡 Tạo `ENUM order_status` và áp dụng cho bảng `orders`.
- 🟡 Thêm cột `total_amount` là generated column (`qty * unit_price STORED`) trong `order_items`.
- 🟡 So sánh thực nghiệm: insert `0.1 + 0.2` vào 1 cột `double precision` và 1 cột `numeric(10,2)`, quan sát sai số.
- 🔴 Tạo `DOMAIN email_type` với `CHECK` regex, áp dụng cho cột email ở 2 bảng khác nhau, sau đó `ALTER DOMAIN` thêm ràng buộc độ dài và quan sát ảnh hưởng đồng thời lên cả 2 bảng.
- 🔴 Thiết kế bảng `products` với `attributes jsonb`, tạo GIN index, so sánh tốc độ query `WHERE attributes @> '{"color":"red"}'` trước/sau khi có index.
- ⚫ Thiết kế `room_bookings(room_id, during tstzrange)` với `EXCLUDE USING gist (room_id WITH =, during WITH &&)`, viết test case chèn 2 khoảng thời gian giao nhau để xác nhận bị chặn.
- ⚫ Thiết kế lại toàn bộ schema domain bán hàng (mục 10) nhưng đổi `order_status` từ ENUM sang bảng lookup `order_statuses(id, code, label)`, phân tích đánh đổi giữa 2 cách tiếp cận (query đơn giản hơn với ENUM vs linh hoạt hơn với bảng lookup khi cần đa ngôn ngữ/thêm trạng thái thường xuyên).
