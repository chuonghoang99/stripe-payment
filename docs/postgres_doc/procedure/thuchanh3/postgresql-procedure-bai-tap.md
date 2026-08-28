# Đề bài thực hành PostgreSQL PROCEDURE

Hoàn thành **hai procedure**, không phải thiết kế bảng:

1. `sp_discount_top_categories`: một transaction atomic, có temp table và dynamic SQL an toàn.
2. `sp_restock_categories_batch`: commit theo từng category, rollback iteration lỗi và có thể chạy tiếp.

File này đã cung cấp Docker PostgreSQL, schema, constraint, index, dữ liệu mẫu, lệnh gọi và kết quả mong đợi. Phần cần tự viết chỉ là hai khối `CREATE OR REPLACE PROCEDURE`.

Đáp án nằm ở cuối tài liệu qua một liên kết riêng; nên tự làm và chạy đủ test trước khi đối chiếu.

---

## 1. Mục tiêu kiến thức

Sau bài này, người học phải giải thích và áp dụng được:

- Ai sở hữu transaction: caller hay procedure.
- Vì sao `ON COMMIT DROP` phù hợp với procedure atomic nhưng không phù hợp với loop có commit giữa chừng.
- Phạm vi rollback khi một iteration thất bại.
- `BEGIN ... EXCEPTION ... END` hoạt động như một subtransaction.
- Gán biến bằng `:=`, `SELECT ... INTO`, `GET DIAGNOSTICS ... ROW_COUNT` và `GET STACKED DIAGNOSTICS`.
- Dùng `format('%I', identifier)` cho tên cột và `EXECUTE ... USING` cho value.
- Vì sao `%I` vẫn cần allowlist để giới hạn đúng object nghiệp vụ.
- Thiết kế batch có thể resume mà không xử lý lại phần đã thành công.

---

## 2. Khởi động PostgreSQL bằng Docker Compose

### 2.1. Cấu hình thực hành

Project đã có [compose.yaml](../../../../compose.yaml) với cấu hình:

| Thuộc tính | Giá trị |
|---|---|
| Docker image | `postgres:15` |
| Compose service | `postgres` |
| Container | `postgres-lab` |
| Database | `postgres` |
| User | `postgres` |
| Cổng từ máy host | `5438` |
| Cổng trong container | `5432` |
| Volume | `pgdata` |

Password lấy từ `POSTGRES_PASSWORD` trong `compose.yaml`. Các giá trị này chỉ dành cho môi trường lab local, không dùng cho production.

### 2.2. Start database

Chạy từ thư mục gốc project:

```bash
docker compose up -d postgres
docker compose ps postgres
```

Chờ cột trạng thái hiển thị `healthy`. Nếu chưa healthy:

```bash
docker compose logs postgres
```

Healthcheck dùng đúng `POSTGRES_USER` và `POSTGRES_DB` của container:

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
```

### 2.3. Kết nối bằng `psql` bên trong container

Cách này không yêu cầu cài `psql` trên máy và không đưa password vào shell history:

```bash
docker compose exec postgres psql -U postgres -d postgres
```

Kiểm tra kết nối trong `psql`:

```sql
\conninfo
SELECT current_database(), current_user, version();
```

Thoát `psql`:

```text
\q
```

Nếu dùng DBeaver, DataGrip hoặc `psql` từ máy host, dùng `localhost:5438`, database `postgres`, user `postgres`, và password trong `compose.yaml`.

### 2.4. Lưu ý về volume

Các biến `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` chỉ khởi tạo database khi data directory còn trống. Nếu đã chạy container với cấu hình cũ, đổi biến trong `compose.yaml` không tự đổi user/password trong volume hiện có.

Muốn tạo lại lab từ đầu:

```bash
docker compose down -v
docker compose up -d postgres
```

> `docker compose down -v` xóa toàn bộ dữ liệu PostgreSQL trong volume của project. Chỉ chạy khi chắc chắn đây là dữ liệu thực hành có thể tạo lại.

---

## 3. Tạo schema và dữ liệu mẫu

Kết nối vào `psql`, sau đó chạy toàn bộ block bên dưới. Script chỉ drop schema `procedure_lab`, không drop database hay schema khác.

```sql
DROP SCHEMA IF EXISTS procedure_lab CASCADE;
CREATE SCHEMA procedure_lab;

CREATE TABLE procedure_lab.categories (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL UNIQUE,
    is_active   boolean NOT NULL DEFAULT true
);

CREATE TABLE procedure_lab.products (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id  integer NOT NULL
                 REFERENCES procedure_lab.categories(id),
    name         text NOT NULL,
    price        numeric(14, 2) NOT NULL CHECK (price > 0),
    sale_price   numeric(14, 2) CHECK (sale_price > 0),
    stock        integer NOT NULL CHECK (stock >= 0),
    is_active    boolean NOT NULL DEFAULT true,
    updated_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (category_id, name)
);

CREATE TABLE procedure_lab.procedure_runs (
    run_id          uuid NOT NULL,
    procedure_name  text NOT NULL,
    status          text NOT NULL
                    CHECK (status IN ('running', 'success', 'completed_with_errors')),
    started_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at     timestamptz,
    PRIMARY KEY (run_id, procedure_name)
);

CREATE TABLE procedure_lab.price_change_audit (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id        uuid NOT NULL,
    product_id    bigint NOT NULL REFERENCES procedure_lab.products(id),
    category_id   integer NOT NULL REFERENCES procedure_lab.categories(id),
    price_column  text NOT NULL CHECK (price_column IN ('price', 'sale_price')),
    old_price     numeric(14, 2) NOT NULL,
    new_price     numeric(14, 2) NOT NULL,
    changed_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (run_id, product_id, price_column)
);

CREATE TABLE procedure_lab.category_job_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id         uuid NOT NULL,
    job_name       text NOT NULL,
    category_id    integer NOT NULL REFERENCES procedure_lab.categories(id),
    status         text NOT NULL CHECK (status IN ('success', 'failed')),
    affected_rows  integer NOT NULL DEFAULT 0 CHECK (affected_rows >= 0),
    sqlstate       text,
    message        text,
    created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (run_id, job_name, category_id)
);

CREATE INDEX idx_products_active_category
    ON procedure_lab.products(category_id)
    WHERE is_active;

INSERT INTO procedure_lab.categories(name)
VALUES ('Điện tử'), ('Gia dụng'), ('Sách'), ('Thể thao');

INSERT INTO procedure_lab.products(
    category_id, name, price, sale_price, stock, is_active
)
VALUES
    (1, 'Laptop',       20000000, NULL,     5, true),
    (1, 'Chuột',          500000, 450000,  30, true),
    (1, 'Tai nghe cũ',    300000, NULL,    10, false),
    (2, 'Nồi chiên',      3000000, NULL,    12, true),
    (2, 'Máy hút bụi',    4000000, 3600000, 8, true),
    (3, 'PostgreSQL',      150000, NULL,   100, true),
    (3, 'Java',            200000, 180000,  60, true),
    (4, 'Thảm yoga',       400000, NULL,    25, true),
    (4, 'Tạ tay',          800000, 720000,  15, true);
```

Không sửa schema, constraint hoặc seed data để làm cho test pass. Nếu chạy lại setup, `DROP SCHEMA ... CASCADE` cũng xóa hai procedure đã viết; cần tạo lại procedure sau đó.

### 3.1. Kiểm tra dữ liệu đầu vào

```sql
SELECT c.id,
       c.name,
       COUNT(*) FILTER (WHERE p.is_active) AS product_count,
       SUM(p.stock) FILTER (WHERE p.is_active) AS total_stock,
       SUM(p.price * p.stock) FILTER (WHERE p.is_active) AS inventory_value
FROM procedure_lab.categories AS c
JOIN procedure_lab.products AS p ON p.category_id = c.id
GROUP BY c.id, c.name
ORDER BY c.id;
```

Kết quả chuẩn:

| category_id | Category | product_count | total_stock | inventory_value |
|---:|---|---:|---:|---:|
| 1 | Điện tử | 2 | 35 | 115,000,000 |
| 2 | Gia dụng | 2 | 20 | 68,000,000 |
| 3 | Sách | 2 | 160 | 27,000,000 |
| 4 | Thể thao | 2 | 40 | 22,000,000 |

Với `p_rank_by = 'inventory_value'` và `p_top_n = 2`, top category phải là `1` và `2`.

---

## 4. Quy định bài làm

Chỉ nộp hai procedure:

```text
procedure_lab.sp_discount_top_categories
procedure_lab.sp_restock_categories_batch
```

Không được:

- Sửa bảng, constraint, seed data hoặc test để che lỗi procedure.
- Nhận nguyên một đoạn SQL như `p_where_clause` rồi ghép vào query.
- Nối trực tiếp input vào dynamic SQL bằng `||`.
- Nuốt lỗi bằng `WHEN OTHERS` mà không log hoặc không xử lý có chủ đích.
- Đặt `COMMIT`/`ROLLBACK` bên trong block đang có `EXCEPTION` handler.

Quy ước tên:

- Input parameter: `p_...`
- Biến cục bộ: `v_...`
- Record vòng lặp: `r_...` hoặc `v_...`

---

## 5. Bài 1 — Giảm giá top category trong một transaction

### 5.1. Bối cảnh

Marketing muốn giảm một cột giá cho các product thuộc những category đứng đầu. Cả đợt giảm giá phải thành công hoặc thất bại cùng nhau: không được có product đã đổi giá nhưng audit chưa ghi, hoặc ngược lại.

### 5.2. Chữ ký bắt buộc

```sql
CREATE OR REPLACE PROCEDURE procedure_lab.sp_discount_top_categories(
    IN p_run_id uuid,
    IN p_top_n integer,
    IN p_discount_percent numeric,
    IN p_rank_by text DEFAULT 'inventory_value',
    IN p_price_column text DEFAULT 'price'
)
LANGUAGE plpgsql
AS $$
    -- Tự viết
$$;
```

Ví dụ gọi:

```sql
CALL procedure_lab.sp_discount_top_categories(
    '11111111-1111-1111-1111-111111111111',
    2,
    10,
    'inventory_value',
    'price'
);
```

### 5.3. Ý nghĩa input

| Input | Ý nghĩa | Hợp lệ |
|---|---|---|
| `p_run_id` | ID duy nhất của lần chạy | Khác `NULL`, chưa dùng cho procedure này |
| `p_top_n` | Số category được chọn | `1..100` |
| `p_discount_percent` | Phần trăm giảm | `0 < value < 100` |
| `p_rank_by` | Metric xếp hạng | `product_count`, `total_stock`, `inventory_value` |
| `p_price_column` | Cột giá cần cập nhật | `price`, `sale_price` |

Input sai phải `RAISE EXCEPTION` với thông báo đủ để biết tham số nào sai.

### 5.4. Định nghĩa top category

Chỉ tính product có `is_active = true`:

```text
product_count   = COUNT(*)
total_stock     = SUM(stock)
inventory_value = SUM(price * stock)
```

Sắp xếp metric được chọn giảm dần. Nếu hai category bằng điểm, category có `category_id` nhỏ hơn đứng trước. Chỉ lấy `p_top_n` category.

### 5.5. Luồng xử lý bắt buộc

Thực hiện theo thứ tự:

1. Validate toàn bộ input trước khi thay đổi dữ liệu.
2. Insert một row vào `procedure_lab.procedure_runs`:
   - `procedure_name = 'sp_discount_top_categories'`
   - `status = 'running'`
3. Nếu cùng `run_id` đã tồn tại cho procedure này, báo lỗi; không chạy giảm giá lần hai.
4. Xóa temp table cũ của session nếu có.
5. Tạo đúng bảng trung gian:

   ```sql
   CREATE TEMP TABLE temp_top_categories
   ON COMMIT DROP
   AS
   SELECT ...;
   ```

6. Temp table phải có bốn cột:
   - `category_id`
   - `product_count`
   - `total_stock`
   - `inventory_value`
7. Vì cột `ORDER BY` thay đổi theo `p_rank_by`, dùng `EXECUTE format(...)` với `%I` sau khi kiểm tra allowlist.
8. Chỉ xử lý product thỏa cả ba điều kiện:
   - Thuộc top category.
   - `is_active = true`.
   - `stock > 0`.
9. Xác định giá cũ:
   - `p_price_column = 'price'`: dùng `price`.
   - `p_price_column = 'sale_price'`: dùng `COALESCE(sale_price, price)`.
10. Tính:

    ```text
    new_price = round(old_price * (1 - p_discount_percent / 100), 2)
    ```

11. Update đúng cột giá động bằng `%I`; bind `new_price` và `product_id` bằng `$1`, `$2` + `USING`.
12. Với mỗi product đã update, insert đúng một row vào `price_change_audit` chứa giá cũ và giá mới.
13. Đổi run thành `success`, gán `finished_at`.

### 5.6. Yêu cầu transaction và bảo mật

- Procedure này **không có** `COMMIT` hoặc `ROLLBACK`.
- Toàn bộ `CALL` là một transaction atomic khi chạy với autocommit; nếu có lỗi, thay đổi giá, audit và run log đều bị rollback.
- `ON COMMIT DROP` phải tự dọn temp table khi transaction kết thúc.
- `p_rank_by` và `p_price_column` phải qua allowlist trước khi dùng `%I`.
- Identifier dùng `%I`; value dùng parameter + `USING`.
- `%I` chống phá cú pháp nhưng không thay thế allowlist nghiệp vụ.

### 5.7. Skeleton gợi ý

```sql
CREATE OR REPLACE PROCEDURE procedure_lab.sp_discount_top_categories(
    IN p_run_id uuid,
    IN p_top_n integer,
    IN p_discount_percent numeric,
    IN p_rank_by text DEFAULT 'inventory_value',
    IN p_price_column text DEFAULT 'price'
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_sql          text;
    v_category_id  integer;
    v_product      record;
    v_new_price    numeric(14, 2);
BEGIN
    -- TODO 1: validate input
    -- TODO 2: insert procedure_runs và chặn duplicate run
    -- TODO 3: CREATE TEMP TABLE ... ON COMMIT DROP AS SELECT ...
    -- TODO 4: loop category/product, dynamic UPDATE và insert audit
    -- TODO 5: update run thành success
END;
$procedure$;
```

### 5.8. Test chấp nhận

Chạy `CALL` mẫu ở mục 5.2, sau đó:

```sql
SELECT procedure_name, status, finished_at IS NOT NULL AS has_finished
FROM procedure_lab.procedure_runs
WHERE run_id = '11111111-1111-1111-1111-111111111111';

SELECT c.name AS category_name,
       p.name AS product_name,
       p.price
FROM procedure_lab.products AS p
JOIN procedure_lab.categories AS c ON c.id = p.category_id
ORDER BY c.id, p.id;

SELECT product_id, price_column, old_price, new_price
FROM procedure_lab.price_change_audit
WHERE run_id = '11111111-1111-1111-1111-111111111111'
ORDER BY product_id;

SELECT to_regclass('pg_temp.temp_top_categories') AS temp_table_after_commit;
```

Kết quả bắt buộc:

| Kiểm tra | Kết quả |
|---|---|
| Run status | `success`, `has_finished = true` |
| Số audit row | `4` |
| Laptop | `18,000,000.00` |
| Chuột | `450,000.00` |
| Nồi chiên | `2,700,000.00` |
| Máy hút bụi | `3,600,000.00` |
| Tai nghe cũ inactive | Vẫn `300,000.00` |
| Temp table sau autocommit | `NULL` |

Test duplicate run: gọi lại đúng `run_id` trên phải báo lỗi và không giảm giá lần hai.

Test input injection:

```sql
CALL procedure_lab.sp_discount_top_categories(
    '22222222-2222-2222-2222-222222222222',
    2,
    10,
    'inventory_value; DROP TABLE procedure_lab.products; --',
    'price'
);
```

Lệnh phải bị reject ở validation. Sau lỗi, xác nhận bảng vẫn tồn tại:

```sql
SELECT to_regclass('procedure_lab.products');
-- procedure_lab.products
```

Nếu chạy các test trong `BEGIN ... COMMIT`, sau một exception phải `ROLLBACK` trước khi chạy câu tiếp theo. Để quan sát `ON COMMIT DROP` dễ nhất, chạy với `psql` autocommit bật.

### 5.9. Test nhánh `sale_price` và các metric xếp hạng khác

Sau test chính, chạy thêm một run cập nhật `sale_price`:

```sql
CALL procedure_lab.sp_discount_top_categories(
    '55555555-5555-5555-5555-555555555555',
    2,
    20,
    'inventory_value',
    'sale_price'
);

SELECT id, name, price, sale_price
FROM procedure_lab.products
WHERE category_id IN (1, 2)
ORDER BY id;

SELECT product_id, old_price, new_price
FROM procedure_lab.price_change_audit
WHERE run_id = '55555555-5555-5555-5555-555555555555'
ORDER BY product_id;
```

Kết quả dựa trên giá đã giảm ở test chính:

| Product | `price` không đổi ở run này | `sale_price` mới |
|---|---:|---:|
| Laptop | 18,000,000.00 | 14,400,000.00 — fallback từ `price` vì sale cũ là `NULL` |
| Chuột | 450,000.00 | 360,000.00 |
| Nồi chiên | 2,700,000.00 | 2,160,000.00 — fallback từ `price` vì sale cũ là `NULL` |
| Máy hút bụi | 3,600,000.00 | 2,880,000.00 |

Test metric động và tie-break trong một transaction tạm, sau đó rollback để không làm đổi dữ liệu bài tiếp theo:

```sql
BEGIN;

-- Mọi category đều có product_count = 2; tie-break phải chọn category 1.
CALL procedure_lab.sp_discount_top_categories(
    '66666666-6666-6666-6666-666666666666',
    1,
    1,
    'product_count',
    'price'
);

SELECT DISTINCT category_id
FROM procedure_lab.price_change_audit
WHERE run_id = '66666666-6666-6666-6666-666666666666';
-- 1

-- total_stock lớn nhất là category 3 với 160 đơn vị tồn.
CALL procedure_lab.sp_discount_top_categories(
    '77777777-7777-7777-7777-777777777777',
    1,
    1,
    'total_stock',
    'price'
);

SELECT DISTINCT category_id
FROM procedure_lab.price_change_audit
WHERE run_id = '77777777-7777-7777-7777-777777777777';
-- 3

ROLLBACK;
```

Hai run `666...` và `777...` không còn trong audit sau `ROLLBACK`.

---

## 6. Bài 2 — Commit theo category, rollback iteration lỗi và resume

### 6.1. Bối cảnh

Kho cần cộng thêm tồn cho mọi category active. Đây là batch dài: một category lỗi không được làm mất kết quả đã commit của category trước. Lần chạy sau với cùng `run_id` chỉ retry category lỗi.

### 6.2. Chữ ký bắt buộc

```sql
CREATE OR REPLACE PROCEDURE procedure_lab.sp_restock_categories_batch(
    IN p_run_id uuid,
    IN p_stock_increment integer,
    IN p_fail_category_id integer DEFAULT NULL
)
LANGUAGE plpgsql
AS $$
    -- Tự viết
$$;
```

`p_fail_category_id` chỉ là test hook: khi trùng category hiện tại, procedure phải phát sinh lỗi giả lập **sau khi đã UPDATE** để chứng minh subtransaction rollback được câu UPDATE đó.

### 6.3. Ý nghĩa input

| Input | Ý nghĩa | Hợp lệ |
|---|---|---|
| `p_run_id` | ID lần chạy/resume | Khác `NULL` |
| `p_stock_increment` | Số lượng cộng thêm | `1..1000` |
| `p_fail_category_id` | Category cần giả lập lỗi | `NULL` hoặc ID cần test; ID không khớp thì không phát sinh lỗi giả lập |

### 6.4. Transaction boundary mong muốn

```text
category 1: UPDATE + log → COMMIT
category 2: UPDATE → lỗi → inner block tự ROLLBACK → ghi log failed → COMMIT
category 3: UPDATE + log → COMMIT
category 4: UPDATE + log → COMMIT
```

`ROLLBACK` ở đây là rollback subtransaction của inner block, không rollback các category đã commit.

### 6.5. Luồng xử lý bắt buộc

1. Validate `p_run_id` và `p_stock_increment` theo mục 6.3. `p_fail_category_id` là test hook nullable; không bắt buộc kiểm tra ID có tồn tại, ID không khớp chỉ có nghĩa là không giả lập lỗi.
2. Insert hoặc cập nhật row trong `procedure_runs` với:
   - `procedure_name = 'sp_restock_categories_batch'`
   - `status = 'running'`
3. Trước vòng lặp, lấy ID category active vào một biến mảng `integer[]`, sắp xếp theo ID.
4. Loại khỏi mảng category đã có log `success` với cùng:
   - `run_id`
   - `job_name = 'restock_categories'`
5. Với từng category, reset biến trạng thái lỗi, `affected_rows`, SQLSTATE và message.
6. Mở inner block `BEGIN ... EXCEPTION ... END`.
7. Trong inner block:
   - Tăng `stock` cho mọi product active thuộc category.
   - Cập nhật `updated_at`.
   - Lấy số row bằng `GET DIAGNOSTICS v_affected_rows = ROW_COUNT`.
   - Nếu không có product active, `RAISE EXCEPTION`.
   - Nếu category bằng `p_fail_category_id`, `RAISE EXCEPTION` sau UPDATE.
8. Trong exception handler:
   - Không viết explicit `ROLLBACK`.
   - Gán trạng thái failed.
   - Gán `affected_rows = 0` vì UPDATE của inner block đã rollback.
   - Lấy `RETURNED_SQLSTATE` và `MESSAGE_TEXT` bằng `GET STACKED DIAGNOSTICS`.
9. Sau inner block, upsert một row `category_job_log` với `success` hoặc `failed`.
10. Đặt `COMMIT AND CHAIN` **sau** log và **ngoài** block có `EXCEPTION`.
11. Sau vòng lặp:
    - Còn log failed: run là `completed_with_errors`.
    - Không còn log failed: run là `success`.
    - Gán `finished_at` và commit lần cuối.

### 6.6. Vì sao dùng mảng, không dùng `ON COMMIT DROP`?

`ON COMMIT DROP` sẽ xóa temp table ngay lần commit đầu tiên. Bài 2 commit sau mỗi category, nên danh sách điều khiển không thể nằm trong temp table loại này.

Danh sách bài lab chỉ có bốn category, vì vậy chụp vào mảng trước vòng lặp là đủ. Với dữ liệu lớn, có thể dùng temp table `ON COMMIT PRESERVE ROWS` và drop tường minh khi kết thúc.

### 6.7. Skeleton gợi ý

```sql
CREATE OR REPLACE PROCEDURE procedure_lab.sp_restock_categories_batch(
    IN p_run_id uuid,
    IN p_stock_increment integer,
    IN p_fail_category_id integer DEFAULT NULL
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_category_ids  integer[];
    v_category_id   integer;
    v_affected_rows integer;
    v_failed        boolean;
    v_sqlstate      text;
    v_message       text;
    v_has_errors    boolean;
BEGIN
    -- TODO 1: validate và ghi procedure_runs
    -- TODO 2: SELECT array_agg(...) INTO v_category_ids

    FOREACH v_category_id IN ARRAY v_category_ids LOOP
        -- TODO 3: reset biến cho iteration

        BEGIN
            -- TODO 4: UPDATE, ROW_COUNT, lỗi giả lập
        EXCEPTION WHEN OTHERS THEN
            -- TODO 5: đánh dấu failed và GET STACKED DIAGNOSTICS
        END;

        -- TODO 6: upsert category_job_log
        -- TODO 7: COMMIT AND CHAIN
    END LOOP;

    -- TODO 8: tính status cuối, update procedure_runs và COMMIT
END;
$procedure$;
```

### 6.8. Test lần 1: category 2 thất bại

Trước khi test, stock ban đầu của các product active là:

| Category | Product | Stock |
|---:|---|---:|
| 1 | Laptop | 5 |
| 1 | Chuột | 30 |
| 2 | Nồi chiên | 12 |
| 2 | Máy hút bụi | 8 |
| 3 | PostgreSQL | 100 |
| 3 | Java | 60 |
| 4 | Thảm yoga | 25 |
| 4 | Tạ tay | 15 |

Gọi procedure:

```sql
CALL procedure_lab.sp_restock_categories_batch(
    '33333333-3333-3333-3333-333333333333',
    5,
    2
);
```

Kiểm tra:

```sql
SELECT status
FROM procedure_lab.procedure_runs
WHERE run_id = '33333333-3333-3333-3333-333333333333'
  AND procedure_name = 'sp_restock_categories_batch';

SELECT category_id, status, affected_rows, sqlstate, message
FROM procedure_lab.category_job_log
WHERE run_id = '33333333-3333-3333-3333-333333333333'
ORDER BY category_id;

SELECT category_id, name, stock
FROM procedure_lab.products
ORDER BY category_id, id;
```

Kết quả sau lần 1:

| Category | Log | affected_rows | Stock mong đợi |
|---:|---|---:|---|
| 1 | `success` | 2 | Laptop `10`, Chuột `35` |
| 2 | `failed` | 0 | Nồi chiên `12`, Máy hút bụi `8` — không đổi |
| 3 | `success` | 2 | PostgreSQL `105`, Java `65` |
| 4 | `success` | 2 | Thảm yoga `30`, Tạ tay `20` |

Run status phải là `completed_with_errors`. Product `Tai nghe cũ` inactive vẫn có stock `10`.

### 6.9. Test lần 2: resume cùng `run_id`

```sql
CALL procedure_lab.sp_restock_categories_batch(
    '33333333-3333-3333-3333-333333333333',
    5,
    NULL
);
```

Kết quả bắt buộc:

- Chỉ category `2` được xử lý lại.
- Log category `2` đổi thành `success`, `affected_rows = 2`.
- Nồi chiên có stock `17`; Máy hút bụi có stock `13`.
- Stock category `1`, `3`, `4` giữ nguyên kết quả lần 1, không tăng thêm lần nữa.
- Tất cả category log đều `success`.
- Run status cuối là `success`.

Query kiểm tra:

```sql
SELECT category_id, status, affected_rows
FROM procedure_lab.category_job_log
WHERE run_id = '33333333-3333-3333-3333-333333333333'
ORDER BY category_id;

SELECT category_id, name, stock
FROM procedure_lab.products
ORDER BY category_id, id;
```

### 6.10. Giới hạn concurrency của bài lab

Lời giải chỉ yêu cầu idempotent cho các lần gọi **tuần tự** cùng `run_id`. Giả định một worker xử lý một `run_id` tại một thời điểm.

Hai session chạy đồng thời cùng `run_id` có thể cùng chụp category chưa success và cộng stock hai lần. Bài production phải có cơ chế claim/lease, advisory lock hoặc hàng đợi công việc; phần đó nằm ngoài phạm vi bài này.

### 6.11. Test transaction control phải ở top-level

Procedure bài 2 có `COMMIT`, nên lệnh sau phải lỗi:

```sql
BEGIN;

CALL procedure_lab.sp_restock_categories_batch(
    '44444444-4444-4444-4444-444444444444',
    1,
    NULL
);

-- ERROR: invalid transaction termination
ROLLBACK;
```

Khi test bình thường, gọi `CALL` trực tiếp ở chế độ autocommit. Nếu gọi từ Spring/JDBC, không bọc procedure này trong `@Transactional`.

---

## 7. Thứ tự thực hành đề xuất

1. `docker compose up -d postgres` và chờ healthy.
2. Kết nối `psql` bằng `docker compose exec`.
3. Chạy setup ở mục 3 đúng một lần.
4. Viết procedure bài 1, chạy test mục 5.8 và 5.9.
5. Viết procedure bài 2, chạy test mục 6.8 rồi 6.9.
6. Chạy test lỗi transaction ở mục 6.11.
7. Đối chiếu đáp án.

Nếu muốn làm lại từ đầu nhưng giữ container/volume, chạy lại setup mục 3 rồi tạo lại hai procedure.

Nếu lưu lời giải vào `solution.sql`, có thể chạy từ host:

```bash
docker compose exec -T postgres \
  psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
  < solution.sql
```

Không đặt test injection cố ý gây lỗi trong cùng file có `ON_ERROR_STOP=1`, nếu vẫn muốn các test phía sau tiếp tục chạy.

---

## 8. Checklist tự chấm

| Tiêu chí | Điểm |
|---|---:|
| Validate đầy đủ, thông báo lỗi chỉ rõ input sai | 1.0 |
| Bài 1 dùng `ON COMMIT DROP` đúng với transaction atomic | 1.0 |
| Gán biến, `ROW_COUNT`, stacked diagnostics đúng | 1.0 |
| Identifier dùng `%I`, value dùng `USING`, identifier có allowlist | 1.5 |
| Bài 1 update và audit nhất quán; lỗi rollback toàn bộ | 1.5 |
| Bài 2 rollback đúng một category lỗi | 1.5 |
| `COMMIT AND CHAIN` nằm ngoài exception block | 1.0 |
| Resume tuần tự không tăng stock lặp | 1.0 |
| Tên biến rõ, code có comment giải thích transaction boundary | 0.5 |

Không đạt nếu:

- Dynamic SQL có thể chèn thêm câu lệnh qua input.
- Bài 1 commit một phần.
- Bài 2 làm mất kết quả của category đã commit.
- Category lỗi vẫn bị tăng stock.
- Resume làm category success tăng stock lần hai.

---

## 9. Dừng môi trường

Dừng container nhưng giữ dữ liệu:

```bash
docker compose down
```

Dừng và xóa toàn bộ volume lab:

```bash
docker compose down -v
```

---

## 10. Đối chiếu và tài liệu tham khảo

- [Đáp án thực hành procedure](./postgresql-procedure-bai-tap-dap-an.md)
- [Lý thuyết PROCEDURE chi tiết](../postgresql-procedure-chi-tiet.md)
- [PostgreSQL Docker Official Image](https://hub.docker.com/_/postgres)
- [Docker Compose healthcheck](https://docs.docker.com/reference/compose-file/services/#healthcheck)
- [PostgreSQL PL/pgSQL Transaction Management](https://www.postgresql.org/docs/15/plpgsql-transactions.html)
- [PostgreSQL Dynamic Commands](https://www.postgresql.org/docs/15/plpgsql-statements.html#PLPGSQL-STATEMENTS-EXECUTING-DYN)
