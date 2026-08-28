# Extensions trong PostgreSQL — Chi tiết đầy đủ

> Domain xuyên suốt ví dụ: hệ thống bán hàng (`customers`, `products`, `orders`, `order_items`).
> Mức độ bài tập: 🟢 Cơ bản — 🟡 Trung bình — 🔴 Nâng cao — ⚫ Chuyên sâu/thực chiến.

---

## 1. Extension là gì

Extension là **gói mở rộng** đóng gói sẵn: kiểu dữ liệu mới, function, operator, index type, hoặc toàn bộ 1 hệ thống con (như scheduler `pg_cron`) — cài vào 1 database cụ thể bằng 1 câu lệnh, không cần biên dịch lại PostgreSQL.

```sql
CREATE EXTENSION IF NOT EXISTS extension_name;
```

- `IF NOT EXISTS` tránh lỗi nếu extension đã được cài trước đó (idempotent — an toàn khi chạy trong migration script nhiều lần).
- Extension được cài **theo từng database**, không phải toàn cluster — cần `CREATE EXTENSION` riêng ở mỗi database muốn dùng.
- Một số extension yêu cầu file `.so`/`.control` đã có sẵn trên server (do DBA cài qua OS package, vd `apt install postgresql-16-cron`) — `CREATE EXTENSION` chỉ "kích hoạt" nó vào database, không tự tải xuống từ internet.

---

## 2. Quyền hạn để cài Extension

```sql
-- Mặc định cần quyền superuser hoặc CREATEROLE + được cấp quyền cụ thể qua pg_extension
-- Từ PG13+, có thể cấp quyền cài 1 số extension "trusted" cho role thường:
GRANT pg_read_all_data TO analyst;  -- ví dụ 1 predefined role khác, không liên quan trực tiếp extension

-- Kiểm tra extension nào được đánh dấu "trusted" (không cần superuser để cài)
SELECT name, trusted FROM pg_available_extensions WHERE trusted = true;
```

Trong môi trường production dùng managed service (RDS, Cloud SQL, Supabase...), quyền cài extension thường bị giới hạn ở danh sách "whitelist" do nhà cung cấp cho phép — cần tra tài liệu riêng của dịch vụ đó.

---

## 3. Khám phá Extension đã cài / có sẵn

```sql
-- Extension ĐÃ CÀI trong database hiện tại
SELECT * FROM pg_extension;

-- Extension CÓ SẴN để cài (đã có file .control trên server, chưa chắc đã CREATE EXTENSION)
SELECT * FROM pg_available_extensions ORDER BY name;

-- Trong psql:
\dx        -- đã cài
\dx+ pgcrypto  -- chi tiết object mà 1 extension mang vào (function, type...)
```

---

## 4. `pgcrypto` — mã hóa & hash

Extension phổ biến nhất cho bảo mật cơ bản: hash mật khẩu, mã hóa dữ liệu, sinh UUID.

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

### 4.1. Hash mật khẩu (không nên tự viết SHA256 tay)

```sql
CREATE TABLE users (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email    text UNIQUE NOT NULL,
    password_hash text NOT NULL
);

-- Lưu mật khẩu: hash bằng bcrypt (gen_salt('bf') = blowfish, có cost factor điều chỉnh được)
INSERT INTO users(email, password_hash)
VALUES ('alice@example.com', crypt('MySecretPass123', gen_salt('bf')));

-- Kiểm tra đăng nhập: so khớp bằng cách hash lại input rồi so sánh
SELECT id FROM users
WHERE email = 'alice@example.com'
  AND password_hash = crypt('MySecretPass123', password_hash);
-- crypt(input, existing_hash) tự trích xuất salt từ existing_hash để hash lại input cho khớp thuật toán
```

`gen_salt('bf', 12)` — tham số thứ 2 là cost factor (mặc định 6, khuyến nghị 10-12 cho production — càng cao càng chậm nhưng càng khó brute-force).

### 4.2. Mã hóa/giải mã dữ liệu 2 chiều (symmetric encryption)

```sql
-- Mã hóa 1 cột nhạy cảm (vd số thẻ, dữ liệu cần đọc lại được — khác hash 1 chiều)
UPDATE customers
SET national_id_encrypted = pgp_sym_encrypt('079203001234', 'my-secret-key')
WHERE id = 1;

SELECT pgp_sym_decrypt(national_id_encrypted, 'my-secret-key') FROM customers WHERE id = 1;
```

⚠️ Key mã hóa **không nên hardcode trong SQL** như ví dụ trên — thực tế nên truyền qua parameter từ application, hoặc dùng cơ chế quản lý secret riêng (Vault, KMS).

### 4.3. Sinh UUID

```sql
SELECT gen_random_uuid();  -- pgcrypto cung cấp hàm này (PG13+ có sẵn trong core, không cần extension)

CREATE TABLE sessions (
    id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id bigint REFERENCES users(id)
);
```

---

## 5. `uuid-ossp` — sinh UUID (extension cũ hơn)

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

SELECT uuid_generate_v4();  -- UUID ngẫu nhiên, tương đương gen_random_uuid()
SELECT uuid_generate_v1();  -- UUID dựa trên timestamp + MAC address
```

**Lưu ý thực tế**: từ PostgreSQL 13, `gen_random_uuid()` đã có sẵn trong `pgcrypto` (và từ PG 13 cũng có ngay trong core qua module `pgcrypto`), nên phần lớn dự án mới **không cần cài `uuid-ossp` nữa**, trừ khi cần cụ thể `uuid_generate_v1()` (UUID có thể sắp xếp theo thời gian tạo, khác v4 hoàn toàn ngẫu nhiên).

---

## 6. `pg_stat_statements` — theo dõi query chậm/tốn tài nguyên

Đây là công cụ **đầu tiên** để trả lời câu hỏi kinh điển: *"làm sao tìm query chậm nhất trong production?"*

```sql
-- Cần thêm vào postgresql.conf (yêu cầu restart server):
-- shared_preload_libraries = 'pg_stat_statements'

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

### Truy vấn top query tốn thời gian nhất

```sql
SELECT
    query,
    calls,
    total_exec_time,                          -- tổng thời gian (ms) cộng dồn qua mọi lần chạy
    mean_exec_time,                            -- thời gian trung bình mỗi lần
    rows,
    (total_exec_time / NULLIF(calls, 0)) AS avg_ms
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;
```

### Top query gọi nhiều lần nhất (không hẳn chậm, nhưng tốn tài nguyên do tần suất)

```sql
SELECT query, calls, mean_exec_time
FROM pg_stat_statements
ORDER BY calls DESC
LIMIT 10;
```

### Reset thống kê (khi cần đo lại từ đầu, vd sau khi deploy fix)

```sql
SELECT pg_stat_statements_reset();
```

⚠️ Query trong bảng này được **chuẩn hóa** (normalize) — nhiều lần gọi cùng 1 câu SQL nhưng khác giá trị tham số (`WHERE id = 1` vs `WHERE id = 2`) sẽ được gộp thành **1 dòng thống kê** với placeholder `$1`, giúp thấy pattern tổng quát thay vì hàng nghìn dòng riêng lẻ.

---

## 7. `pg_trgm` — tìm kiếm mờ (fuzzy search) & tăng tốc `LIKE`/`ILIKE`

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

### Vấn đề cần giải quyết

```sql
-- Query này KHÔNG tận dụng được B-tree index thông thường vì có % ở đầu chuỗi:
SELECT * FROM products WHERE name ILIKE '%laptop%';
-- → luôn Seq Scan, dù đã có index B-tree trên "name"
```

### Giải pháp: GIN/GiST index dựa trên trigram

```sql
CREATE INDEX idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);

-- Giờ query ILIKE '%...%' có thể tận dụng index này
EXPLAIN ANALYZE SELECT * FROM products WHERE name ILIKE '%laptop%';
-- → Bitmap Index Scan thay vì Seq Scan (với bảng đủ lớn)
```

### Tìm kiếm gần đúng theo độ tương đồng (similarity)

```sql
SELECT name, similarity(name, 'laptop pro') AS score
FROM products
ORDER BY score DESC
LIMIT 5;

-- Toán tử % : true nếu similarity vượt ngưỡng (mặc định 0.3, chỉnh bằng SET pg_trgm.similarity_threshold)
SELECT * FROM products WHERE name % 'laptp';  -- gõ sai chính tả vẫn tìm ra "laptop"
```

Ứng dụng thực tế: tính năng "gợi ý sản phẩm gần đúng tên", tìm kiếm chịu lỗi chính tả, autocomplete.

---

## 8. `hstore` — key-value đơn giản (ít dùng hơn từ khi có jsonb)

```sql
CREATE EXTENSION IF NOT EXISTS hstore;

CREATE TABLE products (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attributes hstore  -- 'color=>"red", size=>"L"'
);

INSERT INTO products (attributes) VALUES ('color=>"red", size=>"L"');
SELECT attributes -> 'color' FROM products;  -- 'red'
```

**So với `jsonb`**: `hstore` chỉ hỗ trợ key-value **phẳng** (flat), giá trị luôn là text, không hỗ trợ lồng nhau (nested object/array) như `jsonb`. Trong dự án mới, gần như luôn ưu tiên `jsonb` — `hstore` chỉ còn phù hợp khi cần tương thích ngược với hệ thống cũ.

---

## 9. `pg_cron` — lên lịch chạy job trong database

```sql
CREATE EXTENSION IF NOT EXISTS pg_cron;
-- Yêu cầu shared_preload_libraries = 'pg_cron' trong postgresql.conf + restart
```

### Cú pháp lên lịch (giống cron OS)

```sql
SELECT cron.schedule(
    'refresh-revenue-view',       -- tên job (định danh duy nhất)
    '0 1 * * *',                  -- lịch: 1h sáng mỗi ngày
    'REFRESH MATERIALIZED VIEW mv_monthly_revenue'
);

SELECT cron.schedule(
    'cleanup-audit-logs',
    '0 3 * * 0',                  -- 3h sáng mỗi Chủ nhật
    $$DELETE FROM audit_log WHERE changed_at < now() - interval '90 days'$$
);

SELECT cron.schedule(
    'cancel-stale-orders',
    '*/15 * * * *',               -- mỗi 15 phút
    'CALL sp_cancel_expired_orders()'
);
```

### Quản lý job

```sql
SELECT * FROM cron.job;                -- danh sách job đang lên lịch
SELECT * FROM cron.job_run_details     -- lịch sử chạy, dùng để giám sát job thất bại
ORDER BY start_time DESC LIMIT 20;

SELECT cron.unschedule('cleanup-audit-logs');   -- hủy lịch 1 job
SELECT cron.unschedule(job_id) FROM cron.job WHERE jobname = 'cancel-stale-orders';
```

### Kiểm tra job lỗi

```sql
SELECT jobname, status, return_message, start_time
FROM cron.job_run_details jrd
JOIN cron.job j ON j.jobid = jrd.jobid
WHERE status = 'failed'
ORDER BY start_time DESC;
```

⚠️ `pg_cron` chạy job **bên trong chính PostgreSQL server**, độc lập với ứng dụng — phù hợp cho job liên quan trực tiếp dữ liệu (refresh view, dọn dẹp, archive). Với nhiều instance app chạy song song, đặt job ở application (`@Scheduled` của Spring) dễ chạy trùng nhiều lần nếu thiếu distributed lock — đây là lý do nhiều team đẩy các job "chỉ liên quan dữ liệu" xuống `pg_cron`.

---

## 10. `postgis` — dữ liệu địa lý (nếu cần)

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE stores (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     text NOT NULL,
    location geometry(Point, 4326)  -- SRID 4326 = WGS84 (chuẩn GPS lat/lng)
);

INSERT INTO stores (name, location)
VALUES ('Store A', ST_SetSRID(ST_MakePoint(105.8342, 21.0278), 4326));  -- Hà Nội

-- Tìm cửa hàng trong bán kính 5km từ 1 điểm
SELECT name FROM stores
WHERE ST_DWithin(
    location::geography,
    ST_SetSRID(ST_MakePoint(105.85, 21.03), 4326)::geography,
    5000  -- mét
);

CREATE INDEX idx_stores_location ON stores USING GIST (location);
```

Chỉ cần cài khi hệ thống thực sự có bài toán địa lý (tìm cửa hàng gần nhất, tính khoảng cách, kiểm tra điểm nằm trong vùng...) — extension khá nặng, không nên cài "phòng khi cần".

---

## 11. `btree_gist` — mở rộng B-tree sang GiST, cần cho `EXCLUDE` constraint

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Cần extension này để EXCLUDE constraint hoạt động với cột kiểu thường (int) kết hợp range
CREATE TABLE room_bookings (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id int NOT NULL,
    during  tstzrange NOT NULL,
    EXCLUDE USING gist (room_id WITH =, during WITH &&)
    -- room_id là int (không phải kiểu geometric mặc định của GiST)
    -- → cần btree_gist để GiST hiểu toán tử "=" trên kiểu int
);
```

---

## 12. `pg_partman` — quản lý partition tự động (nếu dùng partitioning)

```sql
CREATE EXTENSION IF NOT EXISTS pg_partman;

-- Tự động tạo partition mới theo tháng cho bảng orders lớn, tự động drop partition cũ
SELECT partman.create_parent(
    p_parent_table => 'public.orders',
    p_control      => 'created_at',
    p_type         => 'range',
    p_interval     => 'monthly'
);
```

Hữu ích khi bảng log/audit/orders phình rất lớn theo thời gian, cần partition theo tháng/quý nhưng không muốn tự viết cron job tạo/xóa partition thủ công.

---

## 13. `dblink` / `postgres_fdw` — kết nối liên database

```sql
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

CREATE SERVER remote_db FOREIGN DATA WRAPPER postgres_fdw
OPTIONS (host 'other-host', dbname 'analytics_db', port '5432');

CREATE USER MAPPING FOR CURRENT_USER SERVER remote_db
OPTIONS (user 'readonly_user', password 'xxx');

CREATE FOREIGN TABLE remote_orders (
    id bigint, customer_id bigint, status text
) SERVER remote_db OPTIONS (schema_name 'public', table_name 'orders');

SELECT * FROM remote_orders LIMIT 10;  -- query xuyên database như bảng local
```

Dùng khi cần join/query dữ liệu giữa 2 database khác nhau (vd tách OLTP và analytics) mà không cần ETL trung gian.

---

## 14. `citext` — chuỗi không phân biệt hoa/thường

```sql
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE customers (
    id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email citext UNIQUE NOT NULL  -- 'A@x.com' và 'a@x.com' được coi là TRÙNG
);

INSERT INTO customers(email) VALUES ('Alice@Example.com');
INSERT INTO customers(email) VALUES ('alice@example.com');
-- ❌ ERROR: duplicate key value violates unique constraint (citext so sánh không phân biệt hoa/thường)
```

Thay thế gọn hơn cho cách làm thủ công `lower(email)` + trigger chuẩn hóa như đã làm ở phần Trigger — nhưng đánh đổi: mọi so sánh trên cột `citext` đều case-insensitive, cần chắc chắn đó là hành vi mong muốn cho toàn bộ cột.

---

## 15. Cập nhật, gỡ bỏ Extension

```sql
-- Xem phiên bản hiện tại và phiên bản mới nhất có sẵn
SELECT extname, extversion FROM pg_extension WHERE extname = 'pg_trgm';
SELECT * FROM pg_available_extension_versions WHERE name = 'pg_trgm';

-- Cập nhật lên phiên bản mới nhất
ALTER EXTENSION pg_trgm UPDATE;
ALTER EXTENSION pg_trgm UPDATE TO '1.6';  -- chỉ định version cụ thể

-- Gỡ bỏ (cẩn thận nếu có object phụ thuộc — index dùng gin_trgm_ops sẽ bị lỗi nếu gỡ pg_trgm)
DROP EXTENSION IF EXISTS pg_trgm;
DROP EXTENSION IF EXISTS pg_trgm CASCADE;  -- xóa luôn các object phụ thuộc (index, ...)
```

---

## 16. Bảng tổng hợp extension theo mục đích sử dụng

| Nhu cầu | Extension |
|---|---|
| Hash mật khẩu, mã hóa dữ liệu | `pgcrypto` |
| Sinh UUID | `pgcrypto` (`gen_random_uuid()`) — ưu tiên hơn `uuid-ossp` |
| Tìm query chậm nhất, giám sát hiệu năng | `pg_stat_statements` |
| Tìm kiếm mờ, tăng tốc `LIKE '%...%'` | `pg_trgm` |
| Key-value đơn giản (hệ thống cũ) | `hstore` (dự án mới nên dùng `jsonb`) |
| Lên lịch job trong DB | `pg_cron` |
| Dữ liệu địa lý, tính khoảng cách | `postgis` |
| `EXCLUDE` constraint với kiểu dữ liệu thường (int, text...) | `btree_gist` |
| Tự động quản lý partition theo thời gian | `pg_partman` |
| Query xuyên nhiều database | `postgres_fdw` / `dblink` |
| Email/username không phân biệt hoa thường | `citext` |

---

## 17. Lỗi/nhầm lẫn thường gặp

| Vấn đề | Nguyên nhân | Cách xử lý |
|---|---|---|
| `CREATE EXTENSION pg_cron` báo lỗi | Chưa thêm vào `shared_preload_libraries` + chưa restart server | Sửa `postgresql.conf`, restart, rồi mới `CREATE EXTENSION` |
| `permission denied to create extension` | Role hiện tại không phải superuser và extension không thuộc danh sách "trusted" | Nhờ DBA cài, hoặc dùng extension trusted (PG13+) |
| `ILIKE '%...%'` vẫn Seq Scan dù đã tạo GIN index | Quên dùng đúng operator class `gin_trgm_ops` khi tạo index | `CREATE INDEX ... USING GIN (col gin_trgm_ops)` |
| Extension cài ở database A nhưng không thấy ở database B | Extension cài theo từng database, không phải toàn cluster | `CREATE EXTENSION` riêng ở từng database cần dùng |
| `DROP EXTENSION` báo lỗi phụ thuộc | Còn index/object đang dùng operator/function của extension đó | Xóa object phụ thuộc trước, hoặc dùng `CASCADE` (cẩn thận) |
| `pg_stat_statements` không thấy dữ liệu | Chưa thêm vào `shared_preload_libraries`, hoặc mới reset | Kiểm tra `SHOW shared_preload_libraries;`, restart nếu cần |

---

## 18. Bài tập thực hành (tăng dần độ khó)

- 🟢 Cài `pgcrypto`, lưu mật khẩu bằng `crypt(password, gen_salt('bf'))`, viết query kiểm tra đăng nhập đúng/sai.
- 🟢 Dùng `\dx` liệt kê extension đã cài, `pg_available_extensions` liệt kê extension có sẵn trên server.
- 🟡 Cài `pg_stat_statements`, chạy vài query nặng (JOIN nhiều bảng), truy vấn bảng thống kê để tìm top 5 query tốn thời gian nhất.
- 🟡 Cài `pg_trgm`, tạo GIN index trên `products.name`, so sánh `EXPLAIN ANALYZE` trước/sau khi có index cho `ILIKE '%...%'`.
- 🟡 Dùng `similarity()` của `pg_trgm` để viết query "gợi ý sản phẩm gần đúng tên" khi người dùng gõ sai chính tả.
- 🔴 Cài `pg_cron`, lên lịch job refresh 1 materialized view mỗi ngày lúc 1h sáng, sau đó cố tình cho job lỗi (vd query sai cú pháp) và kiểm tra `cron.job_run_details` để xác nhận phát hiện được lỗi.
- 🔴 So sánh cách chuẩn hóa email "không phân biệt hoa thường" bằng 2 cách: (a) trigger `lower(email)` thủ công, (b) dùng `citext` — phân tích ưu/nhược điểm.
- ⚫ Dựng thử `postgres_fdw` giữa 2 database local (hoặc 2 schema khác nhau giả lập), viết 1 query join dữ liệu giữa `orders` ở database chính và `foreign table` trỏ sang database phụ.
- ⚫ Thiết lập `btree_gist` + `EXCLUDE` constraint cho bài toán đặt phòng (`room_bookings`), viết test case chèn 2 khoảng thời gian giao nhau để xác nhận bị chặn, rồi thử `DROP EXTENSION btree_gist` để quan sát lỗi phụ thuộc.
