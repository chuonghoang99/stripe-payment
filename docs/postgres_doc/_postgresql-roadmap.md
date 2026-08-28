# Khung kiến thức PostgreSQL — Từ cơ bản đến nâng cao

> Dành cho backend dev (Java/Spring Boot) ôn luyện phỏng vấn & đào sâu PostgreSQL.
> Mỗi phần có: **Lý thuyết cốt lõi** → **Điều cần nhớ khi phỏng vấn** → **Bài tập tăng dần độ khó**.

---

## Cách dùng tài liệu này

- Setup 1 database thực hành, ví dụ `interview_lab`.
- Đề xuất domain xuyên suốt: hệ thống **bán hàng** (customers, products, orders, order_items) — dùng lại cho toàn bộ bài tập để thấy kiến thức nối tiếp nhau.
- Mức độ bài tập: 🟢 Cơ bản — 🟡 Trung bình — 🔴 Nâng cao — ⚫ Chuyên sâu/thực chiến.

```sql
CREATE DATABASE interview_lab;
```

---

## Phần 1 — Data Types & Table Design

### Lý thuyết cốt lõi
- Numeric: `smallint, integer, bigint, numeric(p,s), real, double precision, serial/bigserial (identity cũ), GENERATED ... AS IDENTITY (chuẩn mới)`.
- Text: `char(n)` (hiếm dùng), `varchar(n)`, `text` (Postgres không phạt hiệu năng text vs varchar).
- Thời gian: `date, time, timestamp, timestamptz` — **luôn ưu tiên `timestamptz`**.
- Khác: `boolean, uuid, bytea, json vs jsonb, array (int[], text[]), enum, interval, inet/cidr, money (tránh dùng)`.
- Constraints: `PRIMARY KEY, FOREIGN KEY, UNIQUE, NOT NULL, CHECK, EXCLUDE`.
- `DEFAULT`, `GENERATED ALWAYS AS (...) STORED` (computed column).
- Domain (`CREATE DOMAIN`) để tái sử dụng constraint cho nhiều cột.

### Điều cần nhớ khi phỏng vấn
- Vì sao nên dùng `timestamptz` thay vì `timestamp`? (lưu UTC, tự quy đổi theo timezone client).
- Khác biệt `serial` và `GENERATED ALWAYS AS IDENTITY` (identity kiểm soát chặt hơn, chuẩn SQL).
- `json` vs `jsonb`: `jsonb` lưu binary, có index (GIN), nhanh hơn khi query; `json` giữ nguyên format text, nhanh hơn khi ghi thô.
- `CHECK` chạy ở mức row, `EXCLUDE` dùng để chặn overlap (vd lịch đặt phòng không trùng giờ).

### Bài tập
- 🟢 Tạo bảng `customers(id, full_name, email UNIQUE, created_at timestamptz default now())`.
- 🟢 Tạo bảng `products(id, sku, name, price numeric(12,2) CHECK (price >= 0), tags text[])`.
- 🟡 Tạo `ENUM order_status ('pending','paid','shipped','cancelled')` và dùng trong bảng `orders`.
- 🟡 Thêm cột `total_amount` là **computed column** (`GENERATED ALWAYS AS (qty*unit_price) STORED`) trong `order_items`.
- 🔴 Tạo `DOMAIN email_type AS text CHECK (VALUE ~ '^[^@]+@[^@]+$')` rồi áp dụng cho cột email.
- ⚫ Thiết kế bảng `room_bookings(room_id, during tstzrange)` với `EXCLUDE USING gist (room_id WITH =, during WITH &&)` để chặn đặt phòng trùng thời gian.

---

## Phần 2 — Truy vấn (SELECT, JOIN, Subquery, CTE, Window Function)

### Lý thuyết cốt lõi
- Thứ tự thực thi logic: `FROM → JOIN → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT/OFFSET`.
- JOIN: `INNER, LEFT, RIGHT, FULL OUTER, CROSS, SELF JOIN`, `LATERAL JOIN`.
- Subquery: scalar, correlated, `EXISTS` vs `IN` vs `JOIN`.
- CTE: `WITH ... AS (...)`, CTE đệ quy (`WITH RECURSIVE`).
- Window function: `ROW_NUMBER(), RANK(), DENSE_RANK(), LAG/LEAD, SUM() OVER(PARTITION BY ...)`.
- `GROUPING SETS, ROLLUP, CUBE` cho báo cáo tổng hợp nhiều tầng.

### Điều cần nhớ khi phỏng vấn
- `EXISTS` thường nhanh hơn `IN` với subquery lớn vì dừng sớm (short-circuit).
- `RANK()` để trống thứ hạng khi trùng, `DENSE_RANK()` không để trống.
- CTE trong PostgreSQL từ bản 12 trở đi **không** còn optimization fence mặc định (có thể bị inline) — cần biết `MATERIALIZED`/`NOT MATERIALIZED` để ép hành vi.
- LATERAL JOIN dùng khi subquery bên phải cần tham chiếu cột của bảng bên trái (vd: top-N mỗi nhóm).

### Bài tập
- 🟢 Liệt kê từng khách hàng và tổng số đơn hàng (kể cả khách chưa từng mua) → `LEFT JOIN`.
- 🟢 Tìm khách hàng chưa từng đặt hàng dùng cả `LEFT JOIN ... IS NULL` và `NOT EXISTS`, so sánh kết quả.
- 🟡 Dùng window function tính doanh thu lũy kế theo tháng (`SUM() OVER (ORDER BY month)`).
- 🟡 Lấy 3 sản phẩm bán chạy nhất trong mỗi danh mục bằng `LATERAL JOIN` hoặc `ROW_NUMBER() PARTITION BY category`.
- 🔴 Viết `WITH RECURSIVE` để truy vấn cây danh mục sản phẩm nhiều cấp (category có `parent_id`).
- 🔴 Dùng `GROUPING SETS` để xuất báo cáo doanh thu theo (năm, tháng), (năm), và tổng toàn bộ trong 1 câu query.
- ⚫ Tối ưu một query JOIN 5 bảng chạy chậm: dùng `EXPLAIN (ANALYZE, BUFFERS)` để chẩn đoán và viết lại bằng CTE `MATERIALIZED` hoặc tách subquery.

---

## Phần 3 — View & Materialized View

### Lý thuyết cốt lõi
- `VIEW`: query được lưu tên, không lưu dữ liệu, luôn phản ánh dữ liệu mới nhất.
- `MATERIALIZED VIEW`: lưu kết quả vật lý, cần `REFRESH MATERIALIZED VIEW [CONCURRENTLY]`.
- Updatable view (view đơn giản trên 1 bảng có thể INSERT/UPDATE trực tiếp).
- `WITH CHECK OPTION` để chặn insert/update phá vỡ điều kiện của view.

### Điều cần nhớ khi phỏng vấn
- Materialized view giúp giảm tải cho query nặng lặp lại nhiều lần (dashboard, báo cáo) nhưng **dữ liệu là snapshot**, cần cơ chế refresh (cron/trigger).
- `REFRESH ... CONCURRENTLY` yêu cầu có unique index trên materialized view, tránh khóa đọc trong lúc refresh.

### Bài tập
- 🟢 Tạo view `v_customer_orders_summary` gộp thông tin khách hàng + tổng chi tiêu.
- 🟡 Tạo materialized view `mv_monthly_revenue` và refresh thủ công, đo thời gian query trước/sau.
- 🟡 Tạo unique index trên `mv_monthly_revenue` rồi thử `REFRESH MATERIALIZED VIEW CONCURRENTLY`.
- 🔴 Tạo view có `WITH CHECK OPTION` chỉ cho phép cập nhật đơn hàng ở trạng thái `pending`.

---

## Phần 4 — Function & Procedure (PL/pgSQL)

### Lý thuyết cốt lõi
- `FUNCTION`: trả về giá trị/table, có thể dùng trong `SELECT`, không tự `COMMIT`.
- `PROCEDURE` (PG 11+): dùng `CALL`, có thể chứa `COMMIT/ROLLBACK` bên trong — phù hợp cho batch job dài.
- Kiểu trả về: scalar, `RETURNS TABLE`, `RETURNS SETOF`, `RETURNS TRIGGER`.
- Tham số: `IN, OUT, INOUT, VARIADIC`, default value.
- Xử lý lỗi: `EXCEPTION WHEN ... THEN`, `RAISE EXCEPTION`.
- `SECURITY DEFINER` vs `SECURITY INVOKER`.

### Điều cần nhớ khi phỏng vấn
- Khác biệt cốt lõi FUNCTION vs PROCEDURE: procedure quản lý transaction riêng, function chạy trong transaction của caller.
- `SECURITY DEFINER` chạy với quyền của người tạo function — cẩn thận rủi ro privilege escalation, nên `SET search_path` cố định trong function.

### Bài tập
- 🟢 Viết function `get_customer_total_spent(customer_id int) RETURNS numeric`.
- 🟢 Viết function `RETURNS TABLE(...)` trả về top N sản phẩm bán chạy.
- 🟡 Viết procedure `place_order(...)` gồm: kiểm tra tồn kho, trừ kho, tạo order, có `EXCEPTION` rollback khi hết hàng.
- 🟡 Viết function xử lý lỗi bằng `EXCEPTION WHEN unique_violation THEN ...`.
- 🔴 Viết function `SECURITY DEFINER` cho phép user thường gọi để xem báo cáo tổng (mà không cần quyền SELECT trực tiếp trên bảng gốc), có `SET search_path = public, pg_temp`.
- ⚫ Viết procedure xử lý batch import 10,000 đơn hàng với `COMMIT` định kỳ mỗi 500 dòng để tránh transaction quá lớn.

---

## Phần 5 — Trigger

### Lý thuyết cốt lõi
- `TRIGGER FUNCTION` (`RETURNS TRIGGER`) + `CREATE TRIGGER ... BEFORE/AFTER INSERT/UPDATE/DELETE ON ... FOR EACH ROW/STATEMENT`.
- Biến đặc biệt: `NEW`, `OLD`, `TG_OP`, `TG_TABLE_NAME`.
- `BEFORE` dùng để validate/chỉnh sửa dữ liệu trước khi ghi; `AFTER` dùng để side-effect (audit log, notify).
- Trigger `INSTEAD OF` cho view.
- `FOR EACH STATEMENT` với transition table (`REFERENCING NEW TABLE AS ...`) để xử lý theo lô, tránh gọi trigger từng dòng.

### Điều cần nhớ khi phỏng vấn
- Trigger là "hidden logic" — dễ gây khó debug, nên cân nhắc giữa trigger vs xử lý ở tầng application/service.
- `BEFORE INSERT` có thể sửa `NEW` để thay đổi dữ liệu sắp ghi; `AFTER` không sửa được `NEW` (đã ghi rồi).
- Trigger đệ quy vô hạn: cẩn thận khi trigger tự update chính bảng đó.

### Bài tập
- 🟢 Trigger `BEFORE UPDATE` tự cập nhật cột `updated_at = now()`.
- 🟢 Trigger `BEFORE INSERT` tự chuẩn hóa email về chữ thường.
- 🟡 Trigger `AFTER INSERT/UPDATE/DELETE` ghi log vào bảng `audit_log(table_name, operation, old_data jsonb, new_data jsonb, changed_at)`.
- 🟡 Trigger kiểm tra tồn kho: `BEFORE INSERT ON order_items` nếu `qty > stock` thì `RAISE EXCEPTION`.
- 🔴 Trigger tự động cập nhật `products.stock` khi có đơn hàng mới (dùng `AFTER INSERT`, tránh race condition bằng `SELECT ... FOR UPDATE` trong function).
- ⚫ Dùng `FOR EACH STATEMENT` + transition table để xử lý audit theo lô khi insert hàng loạt, so sánh hiệu năng với `FOR EACH ROW`.

---

## Phần 6 — Job / Scheduling

### Lý thuyết cốt lõi
- PostgreSQL **không có scheduler built-in** thuần túy → dùng extension `pg_cron` hoặc scheduler ngoài (cron OS, Airflow, Spring `@Scheduled`, Quartz).
- `pg_cron`: chạy job theo cú pháp cron ngay trong DB (`cron.schedule('job_name', '0 * * * *', 'CALL my_procedure()')`).
- Cân nhắc: job chạy trong DB (pg_cron) tiện cho tác vụ liên quan trực tiếp đến dữ liệu (refresh materialized view, dọn dữ liệu cũ); job phức tạp về nghiệp vụ nên để ở tầng application.

### Điều cần nhớ khi phỏng vấn
- pg_cron lưu lịch sử job trong bảng `cron.job_run_details` — dùng để giám sát job thất bại.
- Với hệ thống có nhiều instance app, đặt job ở application (`@Scheduled`) dễ gây chạy trùng nhiều lần nếu không có distributed lock — đây là lý do nhiều team đẩy job "chỉ liên quan dữ liệu" xuống DB qua pg_cron.

### Bài tập
- 🟢 Cài `pg_cron`, lên lịch job refresh `mv_monthly_revenue` mỗi ngày lúc 1h sáng.
- 🟡 Tạo job dọn dẹp: xóa `audit_log` cũ hơn 90 ngày, chạy hàng tuần.
- 🔴 Viết procedure gửi thông báo (dùng `NOTIFY`) cho đơn hàng `pending` quá 24h chưa xử lý, lên lịch chạy mỗi giờ, kiểm tra `cron.job_run_details` khi job lỗi.

---

## Phần 7 — Extensions

### Lý thuyết cốt lõi
- Extension phổ biến cần biết:
  - `pgcrypto`: hash mật khẩu (`crypt`, `gen_salt`), mã hóa dữ liệu.
  - `uuid-ossp` / `pgcrypto (gen_random_uuid)`: sinh UUID.
  - `pg_stat_statements`: theo dõi query chậm/tốn tài nguyên nhất.
  - `pg_trgm`: tìm kiếm mờ (fuzzy search), hỗ trợ index `LIKE '%...%'`.
  - `hstore`: key-value đơn giản (ít dùng hơn từ khi có jsonb).
  - `pg_cron`: scheduler (đã nói ở Phần 6).
  - `postgis`: dữ liệu địa lý (nếu cần).
- `CREATE EXTENSION IF NOT EXISTS ...;`

### Điều cần nhớ khi phỏng vấn
- `pg_stat_statements` là công cụ đầu tiên để trả lời câu "làm sao tìm query chậm nhất trong production?".
- `pg_trgm` cho phép tạo GIN/GiST index hỗ trợ `ILIKE '%keyword%'` — vốn không tận dụng được B-tree index thông thường.

### Bài tập
- 🟢 Bật `pgcrypto`, lưu mật khẩu bằng `crypt(password, gen_salt('bf'))`, viết query kiểm tra đăng nhập.
- 🟡 Bật `pg_stat_statements`, chạy vài query nặng, rồi query bảng thống kê để tìm top 5 query tốn thời gian nhất.
- 🟡 Bật `pg_trgm`, tạo GIN index trên `products.name`, so sánh tốc độ `ILIKE '%abc%'` trước/sau khi có index.
- 🔴 Kết hợp `pg_trgm` với hàm `similarity()` để làm tính năng "gợi ý sản phẩm gần đúng tên".

---

## Phần 8 — Role & Permission

### Lý thuyết cốt lõi
- `CREATE ROLE`, `LOGIN`, `PASSWORD`, `NOSUPERUSER`.
- Phân quyền: `GRANT SELECT/INSERT/UPDATE/DELETE ON table TO role`, `REVOKE`.
- Role kế thừa (`GRANT role_a TO role_b` để `role_b` có quyền của `role_a`).
- `GRANT ... ON ALL TABLES IN SCHEMA`, `ALTER DEFAULT PRIVILEGES` (áp quyền tự động cho bảng tạo sau này).
- `PUBLIC` role, schema-level privileges (`USAGE ON SCHEMA`), quyền trên sequence (cần `USAGE`/`SELECT` cho serial).

### Điều cần nhớ khi phỏng vấn
- Quên `GRANT USAGE ON SCHEMA` là lỗi thường gặp khi user không SELECT được dù đã grant table.
- `ALTER DEFAULT PRIVILEGES` giải quyết vấn đề "bảng mới tạo sau không tự có quyền" — rất hay bị hỏi trong phỏng vấn thực chiến.
- Nguyên tắc **least privilege**: tạo role riêng cho ứng dụng (chỉ CRUD cần thiết), role riêng cho report (chỉ SELECT), tách biệt với role admin.

### Bài tập
- 🟢 Tạo role `app_user` chỉ có quyền SELECT/INSERT/UPDATE trên bảng nghiệp vụ, không có quyền DELETE.
- 🟢 Tạo role `readonly_report` chỉ SELECT trên toàn bộ schema.
- 🟡 Dùng `ALTER DEFAULT PRIVILEGES` để mọi bảng tạo sau này trong schema tự động cấp SELECT cho `readonly_report`.
- 🟡 Tạo role group `dev_team`, gán nhiều user vào group, gán quyền theo group thay vì từng user.
- 🔴 Thiết kế phân quyền 3 tầng: `admin` (full), `app_service` (CRUD giới hạn), `analyst` (chỉ SELECT view báo cáo, không được SELECT trực tiếp bảng gốc).

---

## Phần 9 — Row Level Security (RLS)

### Lý thuyết cốt lõi
- `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;`
- `CREATE POLICY policy_name ON table FOR SELECT/INSERT/UPDATE/DELETE USING (...) WITH CHECK (...)`.
- `USING`: điều kiện lọc dòng khi đọc/update/delete. `WITH CHECK`: điều kiện khi ghi (insert/update) — dữ liệu ghi vào phải thỏa.
- Chủ bảng (table owner) và superuser mặc định **bypass RLS** trừ khi `FORCE ROW LEVEL SECURITY`.
- Kết hợp với `current_setting()` / `current_user` để lọc theo tenant (multi-tenant SaaS pattern kinh điển).

### Điều cần nhớ khi phỏng vấn
- RLS là cơ chế bảo mật ở tầng database, hữu ích cho multi-tenant: mỗi tenant chỉ thấy dữ liệu của mình dù dùng chung 1 bảng, ngay cả khi có lỗi ở tầng application quên filter `WHERE tenant_id = ...`.
- Cần `FORCE ROW LEVEL SECURITY` nếu muốn áp cả cho chủ bảng (mặc định owner được miễn).
- RLS có thể ảnh hưởng performance nếu policy phức tạp — nên index cột dùng trong `USING`.

### Bài tập
- 🟢 Thêm cột `tenant_id` vào bảng `orders`, bật RLS, tạo policy chỉ cho xem đơn hàng của `tenant_id = current_setting('app.current_tenant')::int`.
- 🟡 Test bằng 2 role khác nhau, mỗi role set `app.current_tenant` khác nhau, xác nhận không thấy dữ liệu chéo nhau.
- 🟡 Viết policy riêng cho `INSERT` (`WITH CHECK`) đảm bảo user không thể chèn đơn hàng cho tenant khác.
- 🔴 Kết hợp RLS với role: `admin` bypass toàn bộ (dùng `FORCE ROW LEVEL SECURITY` + policy riêng cho role admin xem tất cả), user thường bị giới hạn theo tenant.
- ⚫ Đo hiệu năng query trước/sau khi bật RLS trên bảng lớn (>1 triệu dòng), tối ưu bằng index trên `tenant_id`.

---

## Phần 10 — Index & Performance Tuning

### Lý thuyết cốt lõi
- Loại index: `B-tree` (mặc định, so sánh/khoảng), `GIN` (jsonb, array, full-text, trgm), `GiST` (dữ liệu không gian, range), `BRIN` (bảng cực lớn, dữ liệu có tính tuần tự), `Hash` (chỉ so sánh `=`).
- Partial index (`WHERE status = 'pending'`), composite index (thứ tự cột quan trọng), covering index (`INCLUDE`).
- `EXPLAIN` vs `EXPLAIN ANALYZE` vs `EXPLAIN (ANALYZE, BUFFERS)`.
- Đọc kế hoạch: `Seq Scan` vs `Index Scan` vs `Index Only Scan` vs `Bitmap Heap Scan`.
- `VACUUM`, `ANALYZE`, `autovacuum` — vì sao Postgres cần vacuum (MVCC để lại dead tuples).

### Điều cần nhớ khi phỏng vấn
- Composite index `(a, b)` phục vụ tốt query lọc theo `a` hoặc `(a,b)`, nhưng **không** phục vụ tốt query chỉ lọc theo `b`.
- Index không tự động được dùng nếu optimizer ước tính scan toàn bảng rẻ hơn (bảng nhỏ) — bình thường, không phải index "hỏng".
- MVCC là lý do UPDATE/DELETE tạo dead tuple, cần `VACUUM` để dọn — liên hệ trực tiếp tới "vì sao bảng phình to dù data không tăng".

### Bài tập
- 🟢 Chạy `EXPLAIN ANALYZE` cho 1 query filter theo cột chưa có index, quan sát `Seq Scan`.
- 🟢 Tạo index cho cột đó, chạy lại, so sánh chi phí (`cost`) và thời gian thực thi.
- 🟡 Tạo composite index `(customer_id, created_at)` và test 2 query: lọc theo cả 2 cột vs chỉ lọc `created_at`, giải thích khác biệt.
- 🟡 Tạo partial index `WHERE status = 'pending'` cho bảng orders lớn, so sánh với full index.
- 🔴 Tạo covering index bằng `INCLUDE` để đạt `Index Only Scan`.
- ⚫ Giả lập bảng vài triệu dòng, tối ưu 1 query báo cáo phức tạp (join + group by) từ vài giây xuống dưới 100ms bằng index + rewrite query.

---

## Phần 11 — JSON/JSONB nâng cao

### Lý thuyết cốt lõi
- Toán tử: `->`, `->>`, `#>`, `#>>`, `@>`, `?`, `jsonb_set()`, `jsonb_agg()`, `jsonb_build_object()`.
- Index GIN trên jsonb (`USING GIN (data)` hoặc `jsonb_path_ops` cho `@>` nhanh hơn).
- Dùng jsonb cho dữ liệu bán cấu trúc (product attributes động) mà không phá vỡ schema quan hệ chính.

### Bài tập
- 🟢 Thêm cột `attributes jsonb` vào `products`, lưu thuộc tính động (màu, size...).
- 🟡 Query sản phẩm có `attributes @> '{"color":"red"}'`, tạo GIN index hỗ trợ.
- 🔴 Viết query dùng `jsonb_agg` để gộp toàn bộ `order_items` của 1 đơn hàng thành 1 JSON lồng trong kết quả trả về (giả lập response API).

---

## Phần 12 — Transaction, Isolation & Locking

### Lý thuyết cốt lõi
- ACID, `BEGIN/COMMIT/ROLLBACK`, `SAVEPOINT`.
- Isolation levels: `Read Committed` (mặc định), `Repeatable Read`, `Serializable`.
- Hiện tượng: dirty read (Postgres không bao giờ cho phép), non-repeatable read, phantom read, serialization anomaly.
- Locking: row-level lock (`SELECT ... FOR UPDATE`, `FOR SHARE`), deadlock, `NOWAIT`/`SKIP LOCKED`.

### Điều cần nhớ khi phỏng vấn
- Postgres không có dirty read ở bất kỳ level nào (khác MySQL InnoDB).
- `SKIP LOCKED` là kỹ thuật kinh điển để làm queue xử lý song song (nhiều worker lấy job mà không giành nhau).

### Bài tập
- 🟢 Mô phỏng race condition: 2 transaction cùng trừ kho một sản phẩm mà không lock → dữ liệu sai.
- 🟡 Sửa lại bằng `SELECT ... FOR UPDATE` để đảm bảo tuần tự đúng.
- 🔴 Dựng mô hình "job queue": nhiều worker `SELECT ... FOR UPDATE SKIP LOCKED` lấy job từ bảng `jobs`, đảm bảo không worker nào xử lý trùng job.
- ⚫ Thử nghiệm `SERIALIZABLE` isolation, mô phỏng serialization failure, xử lý retry ở tầng ứng dụng.

---

## Gợi ý lộ trình học (nếu ôn phỏng vấn ngắn hạn)

1. Tuần 1: Phần 1–3 (Types, Table, Query, View).
2. Tuần 2: Phần 4–5 (Function, Procedure, Trigger).
3. Tuần 3: Phần 8–9 (Role, Permission, RLS) — hay bị hỏi ở vị trí backend có tiếp xúc multi-tenant.
4. Tuần 4: Phần 10–12 (Index, Performance, Transaction/Locking) — phần "phân biệt senior vs junior" rõ nhất khi phỏng vấn.
5. Xen kẽ: Phần 6–7 (Job, Extension) khi có thời gian rảnh — ít bị hỏi sâu nhưng hay xuất hiện trong câu hỏi tình huống thực tế.

Muốn mình soạn thêm **bộ câu hỏi phỏng vấn mẫu (có đáp án)** riêng cho từng phần, hay bắt đầu làm bài tập của Phần 1 ngay trong chat?
