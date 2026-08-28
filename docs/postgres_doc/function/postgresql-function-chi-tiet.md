# Function trong PostgreSQL — Chi tiết đầy đủ

> Domain xuyên suốt ví dụ: hệ thống bán hàng (`customers`, `products`, `orders`, `order_items`).
> Mức độ bài tập: 🟢 Cơ bản — 🟡 Trung bình — 🔴 Nâng cao — ⚫ Chuyên sâu/thực chiến.

---

## 1. Cấu trúc cơ bản

```sql
CREATE OR REPLACE FUNCTION function_name(param1 type, param2 type)
RETURNS return_type
LANGUAGE plpgsql
AS $$
DECLARE
    -- khai báo biến cục bộ
BEGIN
    -- logic xử lý
    RETURN something;
END;
$$;
```

- `CREATE OR REPLACE` giúp cập nhật function mà không cần drop trước. Lưu ý: không đổi được **kiểu tham số** hay **return type** bằng `REPLACE` — phải `DROP` rồi tạo lại nếu đổi các thành phần này.
- `LANGUAGE plpgsql` là ngôn ngữ phổ biến nhất (có `IF/LOOP`, biến, exception). Ngoài ra:
  - `sql` — function chỉ có 1 (hoặc vài) câu SQL thuần, optimizer có thể "inline" tốt hơn.
  - `plpython3u`, `plperl`... — ngôn ngữ thủ tục khác (cần cài extension riêng).

---

## 2. Function trả về giá trị đơn (scalar)

```sql
CREATE OR REPLACE FUNCTION get_customer_total_spent(p_customer_id int)
RETURNS numeric
LANGUAGE plpgsql
AS $$
DECLARE
    v_total numeric;
BEGIN
    SELECT COALESCE(SUM(oi.qty * oi.unit_price), 0)
    INTO v_total
    FROM orders o
    JOIN order_items oi ON oi.order_id = o.id
    WHERE o.customer_id = p_customer_id;

    RETURN v_total;
END;
$$;
```

```sql
SELECT get_customer_total_spent(15);

-- dùng ngay trong SELECT như 1 cột bình thường
SELECT id, full_name, get_customer_total_spent(id) AS total_spent
FROM customers;
```

⚠️ **Lưu ý hiệu năng**: cách viết trên gọi function cho từng dòng (row-by-row) — với bảng lớn sẽ chậm hơn nhiều so với viết 1 query JOIN + GROUP BY trực tiếp. Function scalar tiện cho tái sử dụng logic, nhưng không phải lúc nào cũng tối ưu hiệu năng nhất.

---

## 3. Function trả về bảng (`RETURNS TABLE`)

```sql
CREATE OR REPLACE FUNCTION get_top_products(p_limit int DEFAULT 5)
RETURNS TABLE(product_id int, product_name text, total_sold numeric)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT p.id, p.name, SUM(oi.qty)::numeric AS total_sold
    FROM products p
    JOIN order_items oi ON oi.product_id = p.id
    GROUP BY p.id, p.name
    ORDER BY total_sold DESC
    LIMIT p_limit;
END;
$$;
```

```sql
SELECT * FROM get_top_products(3);
```

`p_limit int DEFAULT 5` cho phép gọi `get_top_products()` không cần truyền tham số.

**Điểm đặc biệt**: tên cột khai báo trong `TABLE(...)` **tự động trở thành biến PL/pgSQL** dùng được trong thân hàm:

```sql
CREATE OR REPLACE FUNCTION get_order_summary(p_order_id int)
RETURNS TABLE(item_count int, total_amount numeric)
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT COUNT(*), SUM(qty * unit_price)
    INTO item_count, total_amount     -- gán trực tiếp vào biến trùng tên cột output
    FROM order_items
    WHERE order_id = p_order_id;

    IF total_amount IS NULL THEN
        item_count := 0;
        total_amount := 0;
    END IF;

    RETURN NEXT;    -- trả về đúng 1 dòng từ các biến hiện tại
END;
$$;
```

---

## 4. `RETURNS SETOF` — trả về nhiều dòng của 1 kiểu có sẵn

### 4.1. `SETOF` trên bảng có sẵn

```sql
CREATE OR REPLACE FUNCTION get_orders_by_status(p_status order_status)
RETURNS SETOF orders
LANGUAGE sql
AS $$
    SELECT * FROM orders WHERE status = p_status;
$$;
```

```sql
SELECT * FROM get_orders_by_status('pending');
```

Ưu điểm: nếu bảng `orders` thêm cột mới sau này, function **tự động phản ánh cột mới** vì tham chiếu trực tiếp kiểu bảng.

### 4.2. `SETOF` trên kiểu scalar

```sql
CREATE OR REPLACE FUNCTION get_distinct_customer_ids()
RETURNS SETOF int
LANGUAGE sql
AS $$
    SELECT DISTINCT customer_id FROM orders;
$$;
```

`RETURNS TABLE` không phù hợp cho trường hợp chỉ có 1 cột — `SETOF` gọn hơn nhiều.

### 4.3. `SETOF` trên custom type

```sql
CREATE TYPE customer_stat AS (
    customer_id int,
    full_name   text,
    total_spent numeric
);

CREATE OR REPLACE FUNCTION get_customer_stats()
RETURNS SETOF customer_stat
LANGUAGE sql
AS $$
    SELECT c.id, c.full_name, COALESCE(SUM(oi.qty * oi.unit_price), 0)
    FROM customers c
    LEFT JOIN orders o ON o.customer_id = c.id
    LEFT JOIN order_items oi ON oi.order_id = o.id
    GROUP BY c.id, c.full_name;
$$;
```

### 4.4. `SETOF RECORD` — linh hoạt nhất nhưng ít an toàn nhất

```sql
CREATE OR REPLACE FUNCTION get_stats_generic(p_customer_id int)
RETURNS SETOF RECORD
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY SELECT COUNT(*), SUM(qty*unit_price)
                 FROM order_items oi JOIN orders o ON o.id = oi.order_id
                 WHERE o.customer_id = p_customer_id;
END;
$$;

-- BẮT BUỘC khai rõ kiểu cột khi gọi:
SELECT * FROM get_stats_generic(1) AS t(order_count int, total numeric);
```

### 4.5. `RETURNS SETOF` vs `RETURNS TABLE` — bảng so sánh

| | `RETURNS SETOF <type>` | `RETURNS TABLE(...)` |
|---|---|---|
| Cấu trúc cột | Dùng kiểu đã tồn tại sẵn (bảng, custom type, scalar) | Định nghĩa cột ngay tại chỗ |
| Trả 1 cột duy nhất | Rất tự nhiên: `RETURNS SETOF int` | Không phù hợp |
| Biến output tự động trong thân hàm | Không có, phải tự khai báo | ✅ Có — tên cột = biến luôn |
| Cần tạo `TYPE` riêng | Có, nếu không dùng lại bảng có sẵn | Không cần |
| Hiệu năng | Tương đương nhau, không khác biệt đáng kể | Tương đương |
| Dùng khi nào | Kết quả = nguyên bảng có sẵn, hoặc type tái sử dụng nhiều nơi | Nhiều cột tùy ý, không muốn tạo TYPE, cần logic PL/pgSQL |

### 4.6. `RETURN QUERY` vs `RETURN NEXT`

```sql
-- RETURN QUERY: trả nguyên kết quả 1 câu SELECT
RETURN QUERY SELECT id, name FROM products WHERE price > 100;
```

```sql
-- RETURN NEXT: trả từng dòng một, dùng khi cần vòng lặp/xử lý logic
CREATE OR REPLACE FUNCTION classify_products()
RETURNS TABLE(product_id int, label text)
LANGUAGE plpgsql
AS $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id, price FROM products LOOP
        product_id := r.id;
        IF r.price > 1000000 THEN
            label := 'expensive';
        ELSIF r.price > 100000 THEN
            label := 'medium';
        ELSE
            label := 'cheap';
        END IF;
        RETURN NEXT;
    END LOOP;
    RETURN;
END;
$$;
```

---

## 5. Tham số `IN / OUT / INOUT` và `VARIADIC`

```sql
CREATE OR REPLACE FUNCTION get_order_stats(
    p_customer_id IN int,
    o_total_orders OUT int,
    o_total_amount OUT numeric
)
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT COUNT(*), COALESCE(SUM(oi.qty * oi.unit_price), 0)
    INTO o_total_orders, o_total_amount
    FROM orders o
    JOIN order_items oi ON oi.order_id = o.id
    WHERE o.customer_id = p_customer_id;
END;
$$;
```

```sql
SELECT * FROM get_order_stats(15);
-- trả về 2 cột: total_orders, total_amount, không cần khai báo RETURNS
```

`VARIADIC` — số lượng tham số không cố định:

```sql
CREATE OR REPLACE FUNCTION sum_numbers(VARIADIC nums numeric[])
RETURNS numeric
LANGUAGE sql
AS $$
    SELECT SUM(n) FROM unnest(nums) AS n;
$$;

SELECT sum_numbers(1, 2, 3, 4);  -- = 10
```

Tham số mặc định (`DEFAULT`) và overloading:

```sql
CREATE FUNCTION calc_discount(p_price numeric) RETURNS numeric
LANGUAGE sql AS $$ SELECT p_price * 0.9; $$;

CREATE FUNCTION calc_discount(p_price numeric, p_percent numeric) RETURNS numeric
LANGUAGE sql AS $$ SELECT p_price * (1 - p_percent/100); $$;

SELECT calc_discount(100);        -- dùng bản 1 tham số
SELECT calc_discount(100, 15);    -- dùng bản 2 tham số
```

---

## 6. Xử lý lỗi (`EXCEPTION`)

```sql
CREATE OR REPLACE FUNCTION register_customer(p_email text, p_full_name text)
RETURNS int
LANGUAGE plpgsql
AS $$
DECLARE
    v_id int;
BEGIN
    INSERT INTO customers(email, full_name)
    VALUES (p_email, p_full_name)
    RETURNING id INTO v_id;

    RETURN v_id;

EXCEPTION
    WHEN unique_violation THEN
        RAISE NOTICE 'Email % đã tồn tại', p_email;
        RETURN (SELECT id FROM customers WHERE email = p_email);
    WHEN OTHERS THEN
        RAISE EXCEPTION 'Lỗi không xác định khi đăng ký khách hàng: %', SQLERRM;
END;
$$;
```

- `RAISE EXCEPTION` chủ động ném lỗi — rollback các thay đổi trong function (hoặc tới savepoint gần nhất nếu có `EXCEPTION` bắt bên ngoài), lỗi được đẩy ra transaction cha.
- `RAISE NOTICE/WARNING` chỉ log, không dừng transaction.
- `SQLERRM` — nội dung lỗi dạng text, đọc được (vd `duplicate key value violates unique constraint...`).
- `SQLSTATE` — mã lỗi chuẩn 5 ký tự, dùng để so sánh logic (vd `23505` = unique_violation).
- `WHEN <tên_điều_kiện>` — bắt lỗi theo tên thay vì `OTHERS`, PostgreSQL kiểm tra từ trên xuống, nên `WHEN OTHERS` luôn đặt cuối cùng.

### `GET DIAGNOSTICS` / `GET STACKED DIAGNOSTICS` — chi tiết lỗi/thống kê câu lệnh

```sql
UPDATE products SET stock = stock - 1 WHERE id = 5;
GET DIAGNOSTICS v_count = ROW_COUNT;  -- số dòng vừa bị ảnh hưởng
```

```sql
EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS
        v_message    = MESSAGE_TEXT,
        v_detail     = PG_EXCEPTION_DETAIL,
        v_constraint = CONSTRAINT_NAME,
        v_column     = COLUMN_NAME,
        v_table      = TABLE_NAME;
```

`FOUND` — biến boolean tự động set sau `SELECT INTO`, `UPDATE`, `DELETE`, `FOR` loop... trả lời "có ít nhất 1 dòng ảnh hưởng không":

```sql
SELECT id INTO v_id FROM customers WHERE email = p_email;
IF NOT FOUND THEN
    RAISE EXCEPTION 'Không tìm thấy khách hàng với email %', p_email;
END IF;
```

### `RAISE ... USING` — tự đặt SQLSTATE/message khi ném lỗi

```sql
IF v_stock < p_qty THEN
    RAISE EXCEPTION 'Không đủ hàng: còn % nhưng cần %', v_stock, p_qty
        USING ERRCODE = 'P0001', HINT = 'Vui lòng giảm số lượng hoặc chọn sản phẩm khác';
END IF;
```

Cho phép tầng ứng dụng (Java/Spring) bắt riêng theo `ERRCODE` để phân biệt lỗi nghiệp vụ với lỗi kỹ thuật.

---

## 7. Trigger function (`RETURNS TRIGGER`)

Function đặc biệt, không gọi trực tiếp mà gắn vào `CREATE TRIGGER`:

```sql
CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_orders_updated_at
BEFORE UPDATE ON orders
FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();
```

⚠️ Trigger **bắt buộc phải là FUNCTION** (`RETURNS TRIGGER`) — PROCEDURE không thể gắn làm trigger.

---

## 8. `SECURITY DEFINER` vs `SECURITY INVOKER`

Mặc định function chạy với quyền của **người gọi** (`SECURITY INVOKER`). `SECURITY DEFINER` khiến function chạy với quyền của **người tạo** — hữu ích khi muốn user thường xem báo cáo tổng hợp mà không cấp quyền SELECT trực tiếp lên bảng gốc.

```sql
CREATE OR REPLACE FUNCTION get_revenue_report()
RETURNS TABLE(month date, total numeric)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp  -- luôn set để tránh bị chèn schema độc hại
AS $$
    SELECT date_trunc('month', o.created_at)::date, SUM(oi.qty * oi.unit_price)
    FROM orders o JOIN order_items oi ON oi.order_id = o.id
    GROUP BY 1 ORDER BY 1;
$$;

GRANT EXECUTE ON FUNCTION get_revenue_report() TO analyst;
```

⚠️ `SET search_path` bắt buộc về mặt an toàn — nếu không set, kẻ tấn công có thể tạo bảng/hàm trùng tên ở schema khác để "đánh lừa" function chạy với quyền cao hơn (privilege escalation).

Mặc định `PUBLIC` tự động có `EXECUTE` trên function mới tạo — nên chủ động `REVOKE EXECUTE ... FROM PUBLIC` với function nhạy cảm rồi `GRANT` lại đúng role cần.

---

## 9. Volatility: `IMMUTABLE`, `STABLE`, `VOLATILE`

| Loại | Ý nghĩa | Ví dụ |
|---|---|---|
| `IMMUTABLE` | Cùng input luôn cùng output, không đọc dữ liệu DB | Hàm toán học thuần túy |
| `STABLE` | Không đổi trong 1 câu query, nhưng có đọc dữ liệu | `SELECT` không ghi |
| `VOLATILE` (mặc định) | Có thể đổi kết quả giữa các lần gọi, có ghi dữ liệu | `INSERT/UPDATE`, `now()`, `random()` |

```sql
CREATE OR REPLACE FUNCTION full_name(p_first text, p_last text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT p_first || ' ' || p_last;
$$;
```

Đánh dấu đúng volatility giúp Postgres **cache kết quả trong cùng 1 query** (với `STABLE`/`IMMUTABLE`) thay vì gọi lại nhiều lần — ảnh hưởng thật đến hiệu năng khi function được gọi trong `WHERE` hoặc trên nhiều dòng.

---

## 10. Dynamic SQL trong Function (`EXECUTE`)

```sql
CREATE OR REPLACE FUNCTION fn_count_rows(p_table_name text)
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
    v_count bigint;
BEGIN
    EXECUTE format('SELECT COUNT(*) FROM %I', p_table_name) INTO v_count;
    RETURN v_count;
END;
$$;

SELECT fn_count_rows('orders');
```

- `%I` — identifier (tên bảng/cột), tự escape, chống injection.
- `%L` — literal (giá trị chuỗi).
- `USING` — cách an toàn hơn để truyền tham số động, ưu tiên hơn `%L` khi có thể.

---

## 11. Overloading (cùng tên, khác tham số)

```sql
CREATE FUNCTION notify(p_message text) RETURNS void
LANGUAGE plpgsql AS $$ BEGIN RAISE NOTICE '%', p_message; END; $$;

CREATE FUNCTION notify(p_message text, p_urgent boolean) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_urgent THEN RAISE WARNING 'KHẨN: %', p_message;
    ELSE RAISE NOTICE '%', p_message; END IF;
END; $$;

SELECT notify('Đơn hàng mới');
SELECT notify('Hết hàng!', true);
```

---

## 12. Quyền hạn & Introspection

```sql
GRANT EXECUTE ON FUNCTION get_customer_total_spent(int) TO app_user;
REVOKE EXECUTE ON FUNCTION get_revenue_report() FROM PUBLIC;

-- Đổi thuộc tính không cần sửa code
ALTER FUNCTION get_top_products(int) RENAME TO fn_get_top_products;
ALTER FUNCTION fn_get_top_products(int) SET search_path = public, pg_temp;

-- Kiểm tra
\df+ get_customer_total_spent

SELECT proname, prokind, pronargs
FROM pg_proc
WHERE proname = 'get_customer_total_spent';
-- prokind = 'f' → function (phân biệt với 'p' = procedure)
```

---

## 13. Bài tập thực hành (tăng dần độ khó)

- 🟢 Viết `get_customer_total_spent(customer_id int) RETURNS numeric`.
- 🟢 Viết function `RETURNS TABLE(...)` trả về top N sản phẩm bán chạy.
- 🟡 Viết function xử lý lỗi bằng `EXCEPTION WHEN unique_violation THEN ...` khi đăng ký khách hàng trùng email.
- 🟡 So sánh 3 cách viết cùng 1 bài toán bằng `SETOF <bảng>`, `SETOF <custom type>`, `RETURNS TABLE` — nhận xét khi nào nên dùng cách nào.
- 🔴 Viết function `SECURITY DEFINER` cho phép user thường xem báo cáo tổng mà không cần quyền SELECT trực tiếp trên bảng gốc, có `SET search_path` đúng cách.
- 🔴 Viết function dùng `GET STACKED DIAGNOSTICS` để log chi tiết lỗi (constraint, column, table) vào bảng `error_log` khi insert thất bại.
- ⚫ Viết function dynamic SQL nhận `p_table_name` bất kỳ, đếm số dòng, đảm bảo chống SQL injection bằng `%I`.
- ⚫ Đo hiệu năng: so sánh function `IMMUTABLE` vs không đánh dấu volatility khi gọi trong `WHERE` trên tập dữ liệu lớn, quan sát khác biệt qua `EXPLAIN ANALYZE`.

---

## 14. So sánh chi tiết với PROCEDURE

### 14.1. Bảng phân biệt cốt lõi

| | FUNCTION | PROCEDURE |
|---|---|---|
| Khai báo | Có `RETURNS` | Không có `RETURNS` |
| Gọi | `SELECT fn(...)` | `CALL sp(...)` |
| Dùng trong `SELECT`/`JOIN`/`WHERE` | ✅ Được | ❌ Không bao giờ |
| `COMMIT`/`ROLLBACK` trực tiếp | ❌ Không được, kể cả trong `IF/ELSE` | ✅ Được (khi chạy ở top-level) |
| Trả dữ liệu | `RETURN value`, `RETURNS TABLE`, `SETOF` — trả nhiều dòng được | Chỉ qua `OUT`/`INOUT`, **không có `RETURN value`**, chỉ trả đúng 1 dòng kết quả |
| Chạy trong transaction | Luôn nằm trong transaction của caller | Có thể tự mở/đóng nhiều transaction trong 1 lần gọi |
| Dùng làm trigger function | ✅ Bắt buộc (`RETURNS TRIGGER`) | ❌ Không được |
| Dùng trong `CHECK`, `DEFAULT`, `GENERATED ... AS` | ✅ Được | ❌ Không được |
| `VOLATILE`/`STABLE`/`IMMUTABLE` | Có ý nghĩa (ảnh hưởng query planner) | Không áp dụng |
| Phiên bản hỗ trợ | Từ rất sớm | **Chỉ từ PostgreSQL 11** |
| Phù hợp nhất cho | Validate, tính toán, trả dữ liệu cho query khác dùng | Batch job, migration, job định kỳ, tác vụ cần commit từng phần |

### 14.2. Ví dụ đối chiếu trực tiếp cùng 1 bài toán

```sql
-- FUNCTION: chạy trong transaction của caller, KHÔNG tự COMMIT được
CREATE OR REPLACE FUNCTION fn_place_order(p_customer_id int, p_product_id int, p_qty int)
RETURNS int
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_id int;
BEGIN
    INSERT INTO orders(customer_id, status) VALUES (p_customer_id, 'pending') RETURNING id INTO v_order_id;
    INSERT INTO order_items(order_id, product_id, qty, unit_price)
    SELECT v_order_id, id, p_qty, price FROM products WHERE id = p_product_id;
    RETURN v_order_id;
END;
$$;

-- PROCEDURE: có thể tự COMMIT bên trong — phù hợp cho batch job dài
CREATE OR REPLACE PROCEDURE sp_reprocess_all_pending_orders()
LANGUAGE plpgsql
AS $$
DECLARE
    r RECORD;
    i int := 0;
BEGIN
    FOR r IN SELECT id FROM orders WHERE status = 'pending' LOOP
        UPDATE orders SET status = 'paid' WHERE id = r.id;
        i := i + 1;
        IF i % 500 = 0 THEN
            COMMIT;  -- chỉ PROCEDURE mới làm được điều này
        END IF;
    END LOOP;
    COMMIT;
END;
$$;
```

Nhầm cú pháp sẽ báo lỗi ngay:
```sql
CALL fn_place_order(1, 5, 10);
-- ❌ ERROR: fn_place_order(integer, integer, integer) is not a procedure

SELECT sp_reprocess_all_pending_orders();
-- ❌ ERROR: sp_reprocess_all_pending_orders() is a procedure
```

### 14.3. Vì sao FUNCTION không ROLLBACK được dù có IF/ELSE

```sql
CREATE OR REPLACE FUNCTION fn_test_rollback(p_qty int)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_qty < 0 THEN
        ROLLBACK;  -- ❌ ERROR: invalid transaction termination
    END IF;
END;
$$;
```

Đây **không phải giới hạn logic** — dù `IF/ELSE` phức tạp thế nào, FUNCTION vẫn không có quyền điều khiển transaction, vì nó được thiết kế để nhúng vào **giữa** 1 câu SQL bất kỳ (kể cả nhiều nơi trong 1 transaction đang chạy). Nếu FUNCTION tự ý COMMIT/ROLLBACK sẽ phá vỡ tính toàn vẹn của transaction cha.

**Cách "rollback" đúng trong FUNCTION**: dùng `RAISE EXCEPTION` để hủy các thay đổi (Postgres tự động rollback transaction bao quanh, hoặc tới savepoint gần nhất nếu có `EXCEPTION` block bắt).

```sql
CREATE OR REPLACE FUNCTION fn_place_order_safe(p_customer_id int, p_product_id int, p_qty int)
RETURNS int
LANGUAGE plpgsql
AS $$
DECLARE
    v_stock int;
    v_order_id int;
BEGIN
    SELECT stock INTO v_stock FROM products WHERE id = p_product_id;

    IF v_stock < p_qty THEN
        RAISE EXCEPTION 'Không đủ hàng: còn % nhưng cần %', v_stock, p_qty;
    END IF;

    INSERT INTO orders(customer_id, status) VALUES (p_customer_id, 'pending') RETURNING id INTO v_order_id;
    UPDATE products SET stock = stock - p_qty WHERE id = p_product_id;

    RETURN v_order_id;
END;
$$;
```

### 14.4. PROCEDURE tương đương — ROLLBACK thật sự

```sql
CREATE OR REPLACE PROCEDURE sp_place_order(p_customer_id int, p_product_id int, p_qty int)
LANGUAGE plpgsql
AS $$
DECLARE
    v_stock int;
BEGIN
    SELECT stock INTO v_stock FROM products WHERE id = p_product_id;

    IF v_stock < p_qty THEN
        ROLLBACK;  -- ✅ HỢP LỆ trong procedure (chỉ khi CALL ở top-level)
        RAISE NOTICE 'Không đủ hàng, đã rollback';
    ELSE
        INSERT INTO orders(customer_id, status) VALUES (p_customer_id, 'pending');
        UPDATE products SET stock = stock - p_qty WHERE id = p_product_id;
        COMMIT;
    END IF;
END;
$$;

CALL sp_place_order(1, 5, 100);
```

### 14.5. Bảng quyết định: khi nào chọn FUNCTION, khi nào chọn PROCEDURE

| Tình huống thực tế | Chọn |
|---|---|
| Cần dùng kết quả trong `SELECT`, `WHERE`, `JOIN` | **FUNCTION** — bắt buộc |
| Validate rồi trả về 1 giá trị/1 bảng cho ứng dụng đọc | **FUNCTION** |
| Trigger function | **FUNCTION** (bắt buộc `RETURNS TRIGGER`) |
| Xử lý batch/job hàng loạt, cần commit dần từng phần | **PROCEDURE** |
| Cần COMMIT thật sự giữa chừng trước khi làm bước tiếp theo (để crash không mất phần đã xong) | **PROCEDURE** |
| Chạy định kỳ qua `pg_cron` / tác vụ độc lập, không cần dùng tiếp trong query khác | **PROCEDURE** (gọi bằng `CALL` tự nhiên hơn) |
| Cần trả nhiều `OUT` params kèm khả năng COMMIT giữa chừng | **PROCEDURE** |

### 14.6. Tóm tắt 1 câu

> **FUNCTION** = "một biểu thức có thể nhúng vào query, luôn sống chung số phận với transaction của người gọi." **PROCEDURE** = "một kịch bản độc lập, có quyền tự quyết định phần nào được lưu vĩnh viễn ngay cả khi phần sau gặp lỗi — nhưng đổi lại không bao giờ được nhúng vào query khác."
