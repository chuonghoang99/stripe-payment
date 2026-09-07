# PROCEDURE trong PostgreSQL — Chi tiết đầy đủ

> Domain xuyên suốt ví dụ: hệ thống bán hàng (`customers`, `products`, `orders`, `order_items`).
> Mức độ bài tập: 🟢 Cơ bản — 🟡 Trung bình — 🔴 Nâng cao — ⚫ Chuyên sâu/thực chiến.

---

## 1. Cấu trúc cơ bản

```sql
CREATE OR REPLACE PROCEDURE procedure_name(param1 type, param2 type)
LANGUAGE plpgsql
AS $$
BEGIN
    -- logic xử lý
END;
$$;
```

- Không có `RETURNS` (khác `FUNCTION`).
- Gọi bằng `CALL`, **không** dùng được trong `SELECT`.
- Chỉ có từ **PostgreSQL 11**. Trước đó không có cách nào tự COMMIT/ROLLBACK bên trong PL/pgSQL — đây chính là lý do PROCEDURE ra đời.

```sql
CALL procedure_name(arg1, arg2);
```

---

## 2. FUNCTION vs PROCEDURE — bảng phân biệt cốt lõi

| | FUNCTION | PROCEDURE |
|---|---|---|
| Khai báo | Có `RETURNS` | Không có `RETURNS` |
| Gọi | `SELECT fn(...)` | `CALL sp(...)` |
| Dùng trong SELECT/JOIN/WHERE | ✅ Được | ❌ Không bao giờ |
| `COMMIT`/`ROLLBACK` trực tiếp | ❌ Không được, kể cả trong IF/ELSE | ✅ Được (khi ở top-level) |
| Trả dữ liệu | `RETURN value`, `RETURNS TABLE`, `SETOF` | Chỉ qua `OUT`/`INOUT`, không có `RETURN value` |
| Chạy trong transaction | Luôn nằm trong transaction của caller | Có thể tự mở/đóng nhiều transaction trong 1 lần gọi |
| Dùng làm trigger function | ✅ Bắt buộc phải là FUNCTION (`RETURNS TRIGGER`) | ❌ Không được |
| Dùng trong CHECK constraint / DEFAULT / GENERATED | ✅ Được (biểu thức/function) | ❌ Không được |
| `VOLATILE`/`STABLE`/`IMMUTABLE` | Có ý nghĩa (ảnh hưởng query planner) | Không áp dụng |
| Phù hợp nhất cho | Validate, tính toán, trả dữ liệu cho query khác dùng | Batch job, migration, job định kỳ, tác vụ cần commit từng phần |

### Ví dụ so sánh trực tiếp

```sql
-- FUNCTION: chạy trong transaction của caller, không tự COMMIT được
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

CALL sp_reprocess_all_pending_orders();
```

Thử lẫn cú pháp sẽ báo lỗi ngay:
```sql
CALL get_customer_total_spent(15);
-- ❌ ERROR: get_customer_total_spent(integer) is not a procedure

SELECT sp_reprocess_all_pending_orders();
-- ❌ ERROR: sp_reprocess_all_pending_orders() is a procedure
```

---

## 3. Transaction trong procedure hoạt động thế nào?

Khi `CALL sp_x()` chạy ở top-level, PostgreSQL cho phép procedure kết thúc transaction hiện tại bằng `COMMIT` hoặc `ROLLBACK`. Ngay sau đó PostgreSQL tự mở transaction mới; không viết `START TRANSACTION` bên trong PL/pgSQL.

```text
transaction A → COMMIT/ROLLBACK → PostgreSQL tự mở transaction B → procedure chạy tiếp
```

```sql
CREATE OR REPLACE PROCEDURE sp_demo_txn_boundary()
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE NOTICE 'txid trước commit: %', txid_current();
    INSERT INTO orders(customer_id, status) VALUES (1, 'pending');
    COMMIT;
    RAISE NOTICE 'txid sau commit: %', txid_current();  -- số khác với dòng trên
    INSERT INTO orders(customer_id, status) VALUES (2, 'pending');
    COMMIT;
END;
$$;
```

`txid_current()` đổi số sau mỗi `COMMIT` — bằng chứng procedure đang đi qua nhiều transaction riêng biệt trong 1 lần `CALL`.

### 3.1. Điều kiện để được `COMMIT`/`ROLLBACK`

Transaction control chỉ hợp lệ khi:

- `CALL` hoặc `DO` được gọi ở top-level và client không bọc nó trong transaction thủ công.
- Hoặc chuỗi gọi chỉ gồm `CALL`/`DO` nối tiếp nhau, không có câu lệnh khác chen giữa. Ví dụ `CALL sp_outer()` → `CALL sp_inner()` vẫn cho phép `sp_inner` kết thúc transaction.

Transaction control không hợp lệ khi:

- Client đã chạy `BEGIN` rồi mới `CALL`, hoặc framework gọi procedure bên trong transaction do nó quản lý, chẳng hạn Spring `@Transactional`.
- Có `SELECT function_x()` chen giữa các procedure: `CALL sp_a()` → `SELECT fn_b()` → `CALL sp_c()`.
- Procedure chạy từ function hoặc trigger.
- Lệnh transaction nằm trong block có `EXCEPTION` handler đang hoạt động.
- Procedure được khai báo `SECURITY DEFINER` hoặc có mệnh đề `SET` gắn trên `CREATE PROCEDURE`.

```sql
-- ❌ Lỗi vì FUNCTION luôn atomic
CREATE FUNCTION fn_wrapper() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    CALL sp_place_order(1, 5, 10);  -- nếu sp_place_order có COMMIT bên trong → LỖI
END;
$$;
```

```sql
BEGIN;
    CALL sp_place_order(1, 5, 10);
-- ❌ ERROR: invalid transaction termination
COMMIT;
```

Với ứng dụng Spring/JDBC, hãy chọn rõ **một nơi sở hữu transaction**:

- Ứng dụng sở hữu transaction: procedure không có `COMMIT`/`ROLLBACK`; service dùng `@Transactional`.
- Procedure sở hữu transaction: gọi `CALL` như một statement top-level với autocommit phù hợp; không bọc bằng `@Transactional`.

### 3.2. `COMMIT AND CHAIN` và `ROLLBACK AND CHAIN`

Transaction mới sau `COMMIT`/`ROLLBACK` thường dùng đặc tính mặc định. Khi batch cần giữ các đặc tính của transaction trước đó, dùng phiên bản `AND CHAIN`:

```sql
COMMIT AND CHAIN;
ROLLBACK AND CHAIN;
```

`AND CHAIN` hữu ích khi lặp qua nhiều batch và muốn giữ cùng isolation level. Nó không làm procedure trở thành atomic: các batch đã commit vẫn không thể rollback lại.

---

## 4. `ROLLBACK` trong vòng lặp: hiểu đúng phạm vi

`ROLLBACK` không có nghĩa là “hoàn tác iteration hiện tại”. Nó hoàn tác **mọi thay đổi chưa commit trong transaction hiện tại**, tức là từ transaction boundary gần nhất. Vì vậy:

- Nếu không commit giữa các vòng, rollback ở vòng 10 sẽ hoàn tác cả vòng 1 đến 10.
- Nếu commit sau từng vòng, rollback ở vòng 10 chỉ hoàn tác phần chưa commit của vòng 10; vòng 1 đến 9 đã bền vững.
- Nếu một iteration ghi nhiều bảng, chỉ đặt boundary sau khi toàn bộ invariant của iteration đã đúng.
- Biến PL/pgSQL không phải dữ liệu transaction. Đừng dựa vào việc rollback biến để khôi phục bộ đếm; hãy cập nhật bộ đếm thành công sau `COMMIT`.

### 4.1. Mẫu explicit `ROLLBACK` theo từng item

Ví dụ lấy danh sách ID vào mảng trước khi bắt đầu commit/rollback. Cách này tránh phụ thuộc vào trạng thái cursor của câu `SELECT` đang điều khiển vòng lặp:

```sql
CREATE OR REPLACE PROCEDURE sp_cancel_orders_one_by_one(p_cutoff timestamptz)
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_ids bigint[];
    v_order_id  bigint;
BEGIN
    SELECT COALESCE(array_agg(id ORDER BY id), ARRAY[]::bigint[])
    INTO v_order_ids
    FROM orders
    WHERE status = 'pending'
      AND created_at < p_cutoff;

    FOREACH v_order_id IN ARRAY v_order_ids LOOP
        UPDATE orders
        SET status = 'cancelled'
        WHERE id = v_order_id
          AND status = 'pending';

        IF FOUND THEN
            INSERT INTO order_audit(order_id, action)
            VALUES (v_order_id, 'cancelled');

            COMMIT AND CHAIN;
        ELSE
            -- Hoàn tác mọi thay đổi kể từ boundary gần nhất.
            ROLLBACK AND CHAIN;

            -- Đây đã là transaction mới; log này không bị rollback ở trên.
            INSERT INTO batch_job_errors(entity_id, message)
            VALUES (v_order_id, 'Order không còn ở trạng thái pending');
            COMMIT AND CHAIN;
        END IF;
    END LOOP;
END;
$$;
```

Nếu job bị dừng giữa chừng, các item đã `COMMIT` vẫn còn. Vì vậy batch procedure phải **restartable/idempotent**: điều kiện `WHERE status = 'pending'` giúp lần chạy lại bỏ qua item đã xử lý.

### 4.2. Khi cần all-or-nothing

Không viết `COMMIT`/`ROLLBACK` trong procedure. Khi validation thất bại, `RAISE EXCEPTION`; PostgreSQL sẽ abort transaction để caller quyết định rollback:

```sql
IF p_qty <= 0 THEN
    RAISE EXCEPTION USING
        ERRCODE = '22023',
        MESSAGE = 'p_qty phải lớn hơn 0';
END IF;
```

Đây là lựa chọn phù hợp cho chuyển tiền, chuyển kho hoặc các nghiệp vụ mà không được phép thành công một phần.

---

## 5. `EXCEPTION` là subtransaction, không phải chỗ đặt `ROLLBACK`

Mỗi block `BEGIN ... EXCEPTION ... END` tạo một subtransaction nội bộ. Khi lỗi xảy ra, thay đổi dữ liệu trong block đó tự động bị hoàn tác trước khi handler chạy, nhưng biến PL/pgSQL vẫn giữ giá trị tại thời điểm lỗi.

PostgreSQL không cho kết thúc transaction bằng `COMMIT`/`ROLLBACK` khi một block có exception handler đang hoạt động:

```sql
-- ❌ SAI: COMMIT nằm trong block có EXCEPTION
BEGIN
    UPDATE orders SET status = 'paid' WHERE id = 10;
    COMMIT;
EXCEPTION WHEN OTHERS THEN
    ROLLBACK;
END;
```

`AND CHAIN` **không tạo ngoại lệ** cho quy tắc trên. Nó chỉ yêu cầu transaction mới giữ các đặc tính như isolation level của transaction vừa kết thúc. Vì vậy thay `COMMIT` bằng `COMMIT AND CHAIN`, hoặc `ROLLBACK` bằng `ROLLBACK AND CHAIN`, bên trong block có `EXCEPTION` vẫn sai.

Đoạn sau cũng không hợp lệ:

```sql
BEGIN
    -- xử lý một batch
    COMMIT AND CHAIN;       -- ❌ vẫn nằm trong block có EXCEPTION
EXCEPTION WHEN OTHERS THEN
    ROLLBACK AND CHAIN;     -- ❌ handler vẫn thuộc block đó
END;
```

Muốn bắt lỗi từng iteration rồi tiếp tục, dùng inner block để cô lập lỗi; đặt transaction boundary **sau** inner block:

```sql
CREATE OR REPLACE PROCEDURE sp_process_orders_resilient()
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_ids bigint[];
    v_order_id  bigint;
    v_failed    boolean;
    v_sqlstate  text;
    v_message   text;
BEGIN
    SELECT COALESCE(array_agg(id ORDER BY id), ARRAY[]::bigint[])
    INTO v_order_ids
    FROM orders
    WHERE status = 'pending';

    FOREACH v_order_id IN ARRAY v_order_ids LOOP
        v_failed := false;
        v_sqlstate := NULL;
        v_message := NULL;

        BEGIN
            UPDATE orders
            SET status = 'processing'
            WHERE id = v_order_id
              AND status = 'pending';

            IF NOT FOUND THEN
                RAISE EXCEPTION 'Order % đã được process bởi worker khác', v_order_id;
            END IF;

            INSERT INTO order_audit(order_id, action)
            VALUES (v_order_id, 'processing');
        EXCEPTION WHEN OTHERS THEN
            -- UPDATE và INSERT của inner block đã tự rollback.
            v_failed := true;
            GET STACKED DIAGNOSTICS
                v_sqlstate = RETURNED_SQLSTATE,
                v_message = MESSAGE_TEXT;
        END;

        IF v_failed THEN
            INSERT INTO batch_job_errors(entity_id, sqlstate, message)
            VALUES (v_order_id, v_sqlstate, v_message);
        END IF;

        -- Hợp lệ vì đã ra khỏi block có EXCEPTION.
        COMMIT AND CHAIN;
    END LOOP;
END;
$$;
```

Không bắt `WHEN OTHERS` rồi im lặng. Hoặc ghi đủ context và tiếp tục có chủ đích, hoặc dùng `RAISE;` để ném lại lỗi gốc.

### 5.1. Sửa đúng ví dụ `process_orders_batch`

Trong ví dụ sai, ngoài vị trí `COMMIT AND CHAIN`/`ROLLBACK AND CHAIN`, câu:

```sql
GET DIAGNOSTICS v_count = ROW_COUNT;
```

đặt sau `FOREACH` chỉ đọc số row của câu `UPDATE` cuối cùng, không phải tổng số row của cả batch. Phải lấy `ROW_COUNT` ngay sau từng `UPDATE`, cộng vào biến của batch, rồi chỉ cộng vào output khi toàn bộ inner block thành công.

Phiên bản đúng:

```sql
CREATE OR REPLACE PROCEDURE process_orders_batch(
    IN  p_order_ids  bigint[],
    IN  p_batch_size integer DEFAULT 100,
    OUT o_processed  integer,
    OUT o_failed     integer
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_batch_ids       bigint[];
    v_id              bigint;
    v_start           integer;
    v_last            integer;
    v_row_count       integer;
    v_batch_processed integer;
    v_failed          boolean;
    v_sqlstate        text;
    v_message         text;
BEGIN
    IF p_order_ids IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_order_ids không được NULL';
    END IF;

    IF p_batch_size IS NULL OR p_batch_size NOT BETWEEN 1 AND 10000 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_batch_size phải nằm trong khoảng 1..10000';
    END IF;

    o_processed := 0;
    o_failed := 0; -- số batch lỗi, không phải số order lỗi

    v_start := array_lower(p_order_ids, 1);
    v_last := array_upper(p_order_ids, 1);

    WHILE v_start IS NOT NULL AND v_start <= v_last LOOP
        v_batch_ids := p_order_ids[
            v_start:LEAST(v_start + p_batch_size - 1, v_last)
        ];

        v_batch_processed := 0;
        v_failed := false;
        v_sqlstate := NULL;
        v_message := NULL;

        BEGIN
            FOREACH v_id IN ARRAY v_batch_ids LOOP
                UPDATE orders
                SET status = 'processed',
                    processed_at = clock_timestamp()
                WHERE id = v_id
                  AND status = 'pending';

                -- Phải đọc ngay sau UPDATE hiện tại.
                GET DIAGNOSTICS v_row_count = ROW_COUNT;
                v_batch_processed := v_batch_processed + v_row_count;
            END LOOP;
        EXCEPTION WHEN OTHERS THEN
            -- Toàn bộ UPDATE trong batch hiện tại đã tự rollback.
            -- Biến PL/pgSQL không rollback, nên không dùng
            -- v_batch_processed của nhánh lỗi.
            v_failed := true;

            GET STACKED DIAGNOSTICS
                v_sqlstate = RETURNED_SQLSTATE,
                v_message = MESSAGE_TEXT;
        END;

        IF v_failed THEN
            o_failed := o_failed + 1;
            RAISE WARNING
                'Batch bắt đầu tại phần tử % thất bại [%]: %',
                v_start,
                v_sqlstate,
                v_message;
        ELSE
            o_processed := o_processed + v_batch_processed;
        END IF;

        -- Hợp lệ: đã ra khỏi inner block có EXCEPTION.
        -- Nếu batch lỗi, subtransaction đã hoàn tác batch đó;
        -- transaction hiện tại chỉ còn thông tin xử lý sau lỗi.
        COMMIT AND CHAIN;

        v_start := v_start + p_batch_size;
    END LOOP;

    IF o_processed = 0 THEN
        RAISE NOTICE 'Không có bản ghi nào được xử lý.';
    END IF;
END;
$procedure$;
```

Luồng transaction của mỗi batch:

```text
inner BEGIN
    UPDATE nhiều order
    nếu lỗi → subtransaction tự rollback toàn bộ batch
inner END
    ↓
đánh dấu success/failed và ghi log nếu cần
    ↓
COMMIT AND CHAIN ở outer block
```

Không cần `ROLLBACK AND CHAIN` trong nhánh lỗi vì inner subtransaction đã rollback các thay đổi của batch. Nếu cần lưu lỗi vào bảng, hãy `INSERT` log sau `END`, trước `COMMIT AND CHAIN`.

Procedure này phải được gọi bằng top-level `CALL`; không đặt `CALL` trong một transaction block do client hoặc framework mở sẵn.

Vì procedure có `OUT` parameter, khi gọi trực tiếp bằng SQL vẫn truyền đủ vị trí tham số `OUT`, thường dùng `NULL`:

```sql
CALL process_orders_batch(ARRAY[1, 2, 3, 4, 5]::bigint[], 2, NULL, NULL);
```

---

## 6. Batch job — lý do PROCEDURE tồn tại

```sql
CREATE OR REPLACE PROCEDURE sp_migrate_old_orders(p_batch_size int DEFAULT 500)
LANGUAGE plpgsql
AS $$
DECLARE
    v_processed int := 0;
    v_batch_count int;
BEGIN
    IF p_batch_size NOT BETWEEN 1 AND 10000 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_batch_size phải nằm trong khoảng 1..10000';
    END IF;

    LOOP
        WITH batch AS (
            SELECT id
            FROM orders
            WHERE status = 'pending'
              AND created_at < now() - interval '1 year'
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            LIMIT p_batch_size
        )
        UPDATE orders o
        SET status = 'archived'
        FROM batch
        WHERE o.id = batch.id;

        GET DIAGNOSTICS v_batch_count = ROW_COUNT;
        v_processed := v_processed + v_batch_count;

        COMMIT AND CHAIN;  -- nhả row lock sau từng lô
        RAISE NOTICE 'Đã xử lý % dòng (tổng: %)', v_batch_count, v_processed;

        EXIT WHEN v_batch_count = 0;
    END LOOP;
END;
$$;

CALL sp_migrate_old_orders(1000);
```

**Vì sao quan trọng:** update một transaction rất lớn giữ lock và phiên bản row cũ lâu, làm recovery/replication nặng hơn và khó restart khi lỗi. Chia nhỏ + commit từng lô giải phóng lock sớm; nếu crash giữa chừng, các lô đã commit không bị mất. Cách này không làm tổng lượng WAL biến mất, mà kiểm soát kích thước và thời gian sống của từng transaction.

Checklist cho batch loop:

- Luôn có `ORDER BY` ổn định; không dùng `OFFSET` trên tập dữ liệu đang thay đổi.
- Mỗi lần xử lý phải làm row không còn thỏa điều kiện chọn, nếu không vòng lặp có thể chạy vô hạn.
- `SKIP LOCKED` phù hợp khi nhiều worker được phép chia nhau công việc; nó không đảm bảo thứ tự xử lý tuyệt đối.
- Đặt giới hạn hợp lệ cho batch size để tránh caller truyền `0`, số âm hoặc batch quá lớn.
- Theo dõi `ROW_COUNT`; thoát khi bằng `0`.
- Thiết kế idempotent để gọi lại sau crash không nhân đôi dữ liệu hoặc side effect.

---

## 7. Tham số `IN / INOUT / OUT`

```sql
-- INOUT: vừa truyền vào vừa nhận kết quả ra
CREATE OR REPLACE PROCEDURE sp_apply_discount(
    INOUT p_price numeric,
    IN p_percent numeric
)
LANGUAGE plpgsql
AS $$
BEGIN
    p_price := p_price * (1 - p_percent / 100);
END;
$$;

CALL sp_apply_discount(100, 20);
-- kết quả: p_price = 80
```

```sql
-- OUT: chỉ nhận, không cần giá trị đầu vào có ý nghĩa
CREATE OR REPLACE PROCEDURE sp_get_order_count(
    IN p_customer_id int,
    OUT o_total int
)
LANGUAGE plpgsql
AS $$
BEGIN
    SELECT COUNT(*) INTO o_total FROM orders WHERE customer_id = p_customer_id;
END;
$$;

CALL sp_get_order_count(15, NULL);
-- vẫn phải truyền đủ vị trí tham số kể cả OUT (khác FUNCTION)
```

⚠️ Procedure **không thể** trả về nhiều dòng như `RETURNS TABLE`/`SETOF` của FUNCTION. `CALL` trả một result row khi procedure có tham số `OUT`/`INOUT`; nếu không có output parameter thì không có result row.

---

## 8. Cursor trong Procedure

```sql
CREATE OR REPLACE PROCEDURE sp_process_orders_with_cursor()
LANGUAGE plpgsql
AS $$
DECLARE
    r record;
    v_count int := 0;
BEGIN
    FOR r IN
        SELECT id, customer_id
        FROM orders
        WHERE status = 'pending'
        ORDER BY id
    LOOP
        UPDATE orders SET status = 'processing' WHERE id = r.id;
        v_count := v_count + 1;

        IF v_count % 500 = 0 THEN
            COMMIT AND CHAIN;
        END IF;
    END LOOP;
    COMMIT;
END;
$$;
```

Cursor thường đóng khi transaction kết thúc. Riêng cursor ngầm do `FOR r IN SELECT ... LOOP` tạo ra sẽ tự chuyển thành **holdable cursor** ở lần `COMMIT`/`ROLLBACK` đầu tiên. Khi đó PostgreSQL materialize toàn bộ kết quả còn lại, nên:

- Vòng lặp vẫn chạy sau commit, nhưng có thể tốn nhiều bộ nhớ/temp file nếu tập kết quả lớn.
- Lock row/table do query của cursor nắm sẽ không còn được giữ sau commit.
- Không được transaction control trong cursor loop do câu lệnh không read-only điều khiển, ví dụ `FOR r IN UPDATE ... RETURNING`.

Với batch lớn, mẫu `LIMIT` + khóa tiếp nối (`id > v_last_id`) hoặc thay đổi trạng thái row như mục 6 thường dễ kiểm soát hơn cursor materialize. Tránh `OFFSET` trên tập dữ liệu đang được cập nhật vì có thể bỏ sót hoặc đọc lặp row.

---

## 9. Dynamic SQL (`EXECUTE`) trong Procedure

### 9.1. Gán biến trong PL/pgSQL

```sql
DECLARE
    v_total      numeric := 0;  -- giá trị khởi tạo
    v_order      orders%ROWTYPE;
    v_result     record;
    v_row_count  bigint;
BEGIN
    v_total := v_total + 100;   -- ưu tiên := để phân biệt với SQL

    SELECT *
    INTO STRICT v_order
    FROM orders
    WHERE id = 10;

    GET DIAGNOSTICS v_row_count = ROW_COUNT;
END;
```

- `:=` gán kết quả biểu thức cho biến. PL/pgSQL cũng chấp nhận `=`, nhưng `:=` dễ đọc hơn.
- `SELECT ... INTO variable` gán một row; không có row thì biến nhận `NULL`, nhiều row thì lấy row đầu tiên nếu thiếu `STRICT`.
- `INTO STRICT` yêu cầu đúng một row, nếu không sẽ phát sinh `NO_DATA_FOUND` hoặc `TOO_MANY_ROWS`.
- `%TYPE` và `%ROWTYPE` giúp biến bám theo kiểu cột/bảng, giảm lỗi khi schema đổi.
- `GET DIAGNOSTICS ... ROW_COUNT` lấy số row của lệnh SQL gần nhất. `FOUND` chỉ trả boolean.

Với dynamic SQL, `INTO` thuộc câu lệnh PL/pgSQL `EXECUTE`, không đặt bên trong chuỗi query:

```sql
EXECUTE format('SELECT count(*) FROM %I WHERE status = $1', p_table_name)
INTO v_row_count
USING p_status;
```

### 9.2. `format()` và các placeholder

| Placeholder | Dùng cho | Hành vi |
|---|---|---|
| `%I` | Identifier: schema, bảng, cột | Quote như `quote_ident`; giá trị `NULL` gây lỗi |
| `%L` | Literal cần nhúng vào SQL text | Quote như `quote_nullable`; `NULL` thành từ khóa `NULL` |
| `%s` | Đoạn text thô | Không quote; không dùng trực tiếp với input chưa tin cậy |
| `%%` | Ký tự `%` | Không tiêu thụ argument |

```sql
SELECT format(
    'UPDATE %1$I SET %2$I = $1 WHERE tenant_id = $2',
    'orders',
    'status'
);
-- UPDATE orders SET status = $1 WHERE tenant_id = $2
```

`%1$I`, `%2$I` là placeholder có vị trí, hữu ích khi một identifier xuất hiện nhiều lần. Các `$1`, `$2` bên trong chuỗi không phải placeholder của `format()`; chúng được `EXECUTE ... USING` bind khi chạy.

### 9.3. Identifier dùng `%I`, value dùng `USING`

```sql
CREATE OR REPLACE PROCEDURE sp_truncate_table_dynamic(p_table_name text)
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_table_name NOT IN ('staging_orders', 'staging_payments') THEN
        RAISE EXCEPTION 'Bảng không được phép: %', p_table_name;
    END IF;

    EXECUTE format('TRUNCATE TABLE app.%I', p_table_name);
    COMMIT;
END;
$$;

CALL sp_truncate_table_dynamic('staging_orders');
```

`%I` làm identifier hợp lệ về cú pháp và chặn việc phá chuỗi SQL, nhưng **không phải authorization**. Nếu caller được truyền tên bảng tùy ý, họ vẫn có thể chọn một bảng hợp lệ nhưng không đúng nghiệp vụ. Procedure nhạy cảm phải kiểm tra allowlist hoặc catalog rồi mới `EXECUTE`.

Ví dụ thực tế hơn, cho chọn một cột text đã được allowlist và bind các giá trị bằng `USING`:

```sql
CREATE OR REPLACE PROCEDURE sp_archive_orders(
    p_filter_column text,
    p_filter_value  text,
    p_before        timestamptz
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_sql text;
    v_row_count int;
BEGIN
    IF p_filter_column NOT IN ('status', 'channel') THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = format('Cột lọc không được phép: %s', p_filter_column);
    END IF;

    v_sql := format(
        'UPDATE app.orders
         SET archived = true
         WHERE %I = $1
           AND created_at < $2',
        p_filter_column
    );

    EXECUTE v_sql USING p_filter_value, p_before;

    GET DIAGNOSTICS v_row_count = ROW_COUNT;
    RAISE NOTICE 'Đã archive % order', v_row_count;
END;
$$;
```

Quy tắc chống SQL injection:

1. Không nối input vào query: `EXECUTE '... ' || p_value` là sai.
2. Dùng `%I` cho schema/table/column; parameter `$1`, `$2` + `USING` cho value.
3. Kiểm tra allowlist cho mọi identifier có thể chọn từ bên ngoài.
4. Schema-qualify object cố định (`app.orders`), đặc biệt với `SECURITY DEFINER`.
5. Không nhận nguyên `p_where_clause text` từ caller. Không có cách bind an toàn một mảnh cú pháp SQL tùy ý.
6. Chỉ dùng `%L` khi lệnh utility không hỗ trợ parameter hoặc cần sinh DDL; nếu `USING` dùng được thì ưu tiên `USING` để giữ nguyên kiểu dữ liệu.
7. `EXECUTE` lập execution plan mới mỗi lần chạy; chỉ dùng dynamic SQL khi cấu trúc câu lệnh thực sự thay đổi.

### 9.4. Schema và bảng động

Không truyền chuỗi `public.orders` vào một `%I`, vì nó sẽ thành identifier duy nhất `"public.orders"`. Truyền schema và tên bảng riêng:

```sql
EXECUTE format(
    'DELETE FROM %I.%I WHERE created_at < $1',
    p_schema_name,
    p_table_name
) USING p_cutoff;
```

Sau khi quote, vẫn phải kiểm tra allowlist, quyền gọi procedure, và các bảng mục tiêu có đúng cấu trúc mong đợi hay không.

---

## 10. CTE hay temporary table trong procedure?

### 10.1. Phạm vi sử dụng

| Tiêu chí | CTE (`WITH`) | Temporary table |
|---|---|---|
| Phạm vi | Chỉ một statement | Session hiện tại, tùy `ON COMMIT` |
| Tái sử dụng ở nhiều statement | Không | Có |
| Index riêng | Không | Có thể tạo index và `ANALYZE` |
| Chi phí setup | Thấp | Có DDL/catalog overhead |
| Tự dọn | Kết thúc statement | Cuối session hoặc theo `ON COMMIT` |
| Phù hợp | Pipeline một câu SQL | Snapshot trung gian được join/update nhiều lần |

CTE phù hợp khi dữ liệu trung gian chỉ dùng một lần:

```sql
WITH top_categories AS (
    SELECT category_id, SUM(quantity * unit_price) AS revenue
    FROM order_items
    GROUP BY category_id
    ORDER BY revenue DESC
    LIMIT p_top_n
)
UPDATE categories c
SET is_featured = true
FROM top_categories t
WHERE c.id = t.category_id;
```

CTE không mặc định là “temp table có tên”. Với CTE side-effect-free, planner có thể inline hoặc materialize tùy số lần tham chiếu và chỉ dẫn `MATERIALIZED`/`NOT MATERIALIZED`.

### 10.2. `CREATE TEMP TABLE ... ON COMMIT DROP AS SELECT`

Khi cần dùng kết quả qua nhiều statement:

```sql
CREATE TEMP TABLE temp_top_categories
ON COMMIT DROP
AS
SELECT p.category_id,
       COUNT(*) AS product_count,
       SUM(p.stock) AS total_stock,
       SUM(p.price * p.stock) AS inventory_value
FROM products p
WHERE p.is_active
GROUP BY p.category_id
ORDER BY inventory_value DESC, p.category_id
LIMIT p_top_n;

CREATE INDEX ON temp_top_categories(category_id);
ANALYZE temp_top_categories;
```

`ON COMMIT` chỉ dùng cho temporary table:

- `PRESERVE ROWS`: giữ row sau commit; đây là mặc định của PostgreSQL.
- `DELETE ROWS`: tự xóa toàn bộ row sau mỗi commit.
- `DROP`: xóa cả bảng ở transaction boundary kế tiếp.

Điểm dễ nhầm: `ON COMMIT DROP` nghĩa là **drop ở lần commit đầu tiên**, không phải “khi procedure chạy xong”. Vì vậy mẫu sau sai nếu vòng 2 còn cần temp table:

```sql
CREATE TEMP TABLE temp_top_categories ON COMMIT DROP AS
SELECT ...;

FOR r IN SELECT * FROM temp_top_categories LOOP
    PERFORM process_category(r.category_id);
    COMMIT; -- temp_top_categories bị drop ngay tại đây
END LOOP;
```

Chọn theo transaction strategy:

- Procedure atomic, không tự commit: dùng `ON COMMIT DROP` rất hợp lý.
- Procedure commit theo batch: dùng `ON COMMIT PRESERVE ROWS` rồi `DROP TABLE` tường minh khi xong, hoặc chụp ID vào array trước vòng lặp.
- Chỉ dùng dữ liệu trong một statement: ưu tiên CTE, tránh tạo temp table không cần thiết.

Nếu procedure có thể được gọi nhiều lần trong cùng connection pool, xử lý tên cũ trước khi tạo:

```sql
DROP TABLE IF EXISTS pg_temp.temp_top_categories;

CREATE TEMP TABLE temp_top_categories
ON COMMIT PRESERVE ROWS
AS SELECT ...;
```

Temp table tách biệt theo session, nhưng nó có thể che khuất bảng cùng tên qua `search_path`. Trong procedure nhạy cảm, schema-qualify các bảng thật và không dựa vào object được resolve mơ hồ.

---

## 11. Gọi Procedure lồng nhau (nested call)

```sql
CREATE OR REPLACE PROCEDURE sp_outer()
LANGUAGE plpgsql
AS $$
BEGIN
    CALL sp_inner();  -- procedure con
    COMMIT;
END;
$$;

CREATE OR REPLACE PROCEDURE sp_inner()
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO orders(customer_id, status) VALUES (1, 'pending');
    -- Quy ước thiết kế: transaction boundary do sp_outer sở hữu.
END;
$$;
```

Một chuỗi `CALL` lồng trực tiếp như trên vẫn được transaction control. Điều làm transaction control bị cấm là có câu lệnh khác chen giữa, ví dụ `CALL sp_outer()` → `SELECT fn_wrapper()` → `CALL sp_inner()`.

Dù PostgreSQL cho phép procedure con commit, best practice vẫn là quy định rõ procedure nào sở hữu transaction boundary. Procedure phụ trợ trung lập với transaction thường dễ tái sử dụng và test hơn.

---

## 12. Exception diagnostics trong Procedure

```sql
CREATE OR REPLACE PROCEDURE sp_cancel_order(p_order_id int)
LANGUAGE plpgsql
AS $$
DECLARE
    v_row_count int;
BEGIN
    UPDATE orders SET status = 'cancelled'
    WHERE id = p_order_id AND status = 'pending';

    GET DIAGNOSTICS v_row_count = ROW_COUNT;

    IF v_row_count = 0 THEN
        RAISE EXCEPTION 'Không thể hủy đơn hàng %: không tồn tại hoặc không ở trạng thái pending', p_order_id;
    END IF;

    RAISE NOTICE 'Đã hủy đơn hàng %', p_order_id;
END;
$$;
```

Procedure trên để caller sở hữu transaction. Nếu caller dùng transaction thủ công, `RAISE EXCEPTION` làm transaction chuyển sang trạng thái lỗi và caller phải rollback. Với top-level statement ở chế độ autocommit, PostgreSQL tự rollback statement transaction bị lỗi.

### Bảng biến/lệnh diagnostics hay dùng

| Cần biết | Dùng |
|---|---|
| Có ít nhất 1 dòng bị ảnh hưởng không (boolean) | `FOUND` |
| Chính xác bao nhiêu dòng bị ảnh hưởng | `GET DIAGNOSTICS v_count = ROW_COUNT` |
| Nội dung lỗi ngắn gọn trong `EXCEPTION` | `SQLERRM` |
| Mã lỗi chuẩn 5 ký tự để so sánh logic | `SQLSTATE` |
| Chi tiết lỗi đầy đủ (constraint, column, detail) để log audit | `GET STACKED DIAGNOSTICS` |

```sql
EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS
        v_sqlstate   = RETURNED_SQLSTATE,
        v_message    = MESSAGE_TEXT,
        v_detail     = PG_EXCEPTION_DETAIL,
        v_hint       = PG_EXCEPTION_HINT,
        v_context    = PG_EXCEPTION_CONTEXT,
        v_constraint = CONSTRAINT_NAME,
        v_column     = COLUMN_NAME,
        v_table      = TABLE_NAME;
```

Một số `SQLSTATE`/tên điều kiện hay gặp:

| SQLSTATE | Tên gọi (dùng trong `WHEN`) | Ý nghĩa |
|---|---|---|
| `23505` | `unique_violation` | Vi phạm UNIQUE constraint |
| `23503` | `foreign_key_violation` | Vi phạm khóa ngoại |
| `23502` | `not_null_violation` | Cột NOT NULL bị để trống |
| `23514` | `check_violation` | Vi phạm CHECK constraint |
| `40001` | `serialization_failure` | Lỗi transaction ở mức SERIALIZABLE |
| `40P01` | `deadlock_detected` | Deadlock |

```sql
EXCEPTION
    WHEN unique_violation THEN
        RAISE NOTICE 'Trùng dữ liệu';
    WHEN foreign_key_violation THEN
        RAISE EXCEPTION 'Dữ liệu tham chiếu không hợp lệ';
    WHEN OTHERS THEN
        RAISE NOTICE 'Lỗi khác: % (%)', SQLERRM, SQLSTATE;
        RAISE;  -- ném lại lỗi gốc, không "nuốt" lỗi
```

---

## 13. PROCEDURE với `pg_cron`

```sql
CREATE OR REPLACE PROCEDURE sp_cleanup_old_audit_logs()
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM audit_log WHERE changed_at < now() - interval '90 days';
    COMMIT;
END;
$$;

SELECT cron.schedule('cleanup-audit-logs', '0 2 * * *', 'CALL sp_cleanup_old_audit_logs()');
```

`pg_cron` gọi procedure ở top-level (như 1 session độc lập) → `COMMIT` bên trong hoạt động bình thường. Đây là lý do PROCEDURE thường được ưu tiên hơn FUNCTION cho job định kỳ, đặc biệt job xử lý dữ liệu lớn cần commit theo lô.

---

## 14. `DO` block — anh em gần với Procedure

```sql
DO $$
BEGIN
    UPDATE orders SET status = 'cancelled' WHERE status = 'pending' AND created_at < now() - interval '30 days';
    COMMIT;
    RAISE NOTICE 'Đã hủy các đơn hàng quá hạn';
END;
$$;
```

Khác PROCEDURE: `DO` không có tên, không lưu trong catalog, không nhận tham số, chỉ chạy 1 lần rồi thôi — phù hợp cho script chạy tay 1 lần (migration, fix dữ liệu tạm thời), không phù hợp để tái sử dụng. Vẫn cho phép `COMMIT`/`ROLLBACK` vì cũng chạy độc lập ở top-level.

---

## 15. Quyền hạn (Permissions)

```sql
-- Cấp quyền thực thi
GRANT EXECUTE ON PROCEDURE sp_cancel_expired_orders() TO app_service;

-- Thu hồi
REVOKE EXECUTE ON PROCEDURE sp_cancel_expired_orders() FROM PUBLIC;

-- Đổi chủ sở hữu (ảnh hưởng SECURITY DEFINER — chạy với quyền của owner)
ALTER PROCEDURE sp_cancel_expired_orders() OWNER TO admin_role;
```

⚠️ Mặc định khi tạo procedure mới, `PUBLIC` (mọi role) tự động có quyền `EXECUTE` — rủi ro bảo mật hay bị bỏ sót. Nên `REVOKE EXECUTE ... FROM PUBLIC` ngay sau khi tạo procedure nhạy cảm rồi `GRANT` lại đúng role cần thiết.

### `SECURITY DEFINER` cho Procedure

```sql
CREATE OR REPLACE PROCEDURE sp_admin_reset_stock(p_product_id int, p_new_stock int)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app, pg_temp
AS $$
BEGIN
    UPDATE app.products SET stock = p_new_stock WHERE id = p_product_id;
END;
$$;
```

Với `SECURITY DEFINER`, phải cố định `search_path` chỉ gồm schema tin cậy và nên schema-qualify object để tránh privilege escalation qua object giả mạo. Procedure `SECURITY DEFINER`, hoặc procedure có `SET` clause gắn trên định nghĩa, **không được** thực hiện transaction control; vì vậy ví dụ không có `COMMIT`.

---

## 16. `ALTER PROCEDURE`

```sql
-- Đổi tên
ALTER PROCEDURE sp_cancel_expired_orders() RENAME TO sp_cancel_stale_orders;

-- Set search_path cố định
ALTER PROCEDURE sp_cancel_stale_orders() SET search_path = public, pg_temp;

-- Chuyển schema
ALTER PROCEDURE sp_cancel_stale_orders() SET SCHEMA maintenance;
```

Lưu ý: `ALTER` không đổi được thân code — sửa logic bên trong vẫn phải dùng `CREATE OR REPLACE PROCEDURE`.

---

## 17. Introspection

```sql
-- Trong psql
\df+ sp_cancel_stale_orders

-- Query trực tiếp catalog
SELECT proname, prokind, pronargs, proargnames
FROM pg_proc
WHERE proname = 'sp_cancel_stale_orders';
```

`prokind`: `'f'` = function, `'p'` = **procedure**, `'a'` = aggregate, `'w'` = window function.

---

## 18. Overloading và tham số mặc định

```sql
CREATE PROCEDURE sp_notify(p_message text) LANGUAGE plpgsql AS $$
BEGIN RAISE NOTICE 'Thông báo: %', p_message; END; $$;

CREATE PROCEDURE sp_notify(p_message text, p_urgent boolean DEFAULT false) LANGUAGE plpgsql AS $$
BEGIN
    IF p_urgent THEN
        RAISE WARNING 'KHẨN: %', p_message;
    ELSE
        RAISE NOTICE 'Thông báo: %', p_message;
    END IF;
END; $$;

CALL sp_notify('Đơn hàng mới');              -- gọi bản 1 tham số
CALL sp_notify('Hết hàng!', true);           -- gọi bản 2 tham số
```

---

## 19. Bảng tổng hợp giới hạn của Procedure

| Giới hạn | Chi tiết |
|---|---|
| Không dùng trong `SELECT`/`WHERE`/`JOIN` | Chỉ gọi qua `CALL` |
| Không dùng làm trigger function | Trigger bắt buộc là FUNCTION `RETURNS TRIGGER` |
| Không dùng trong `CHECK`, `DEFAULT`, `GENERATED ... AS` | Những chỗ này chỉ chấp nhận biểu thức/FUNCTION |
| Không trả về nhiều dòng | Có `OUT`/`INOUT` thì `CALL` trả một result row; không có output parameter thì không trả result row |
| COMMIT/ROLLBACK phụ thuộc call stack | Chuỗi `CALL`/`DO` trực tiếp được phép; có command khác chen giữa thì bị cấm |
| COMMIT/ROLLBACK không được đặt bên trong chính block có `EXCEPTION` | Được đặt sau `END` của inner block, khi subtransaction đã kết thúc |
| `SECURITY DEFINER` hoặc procedure có `SET` clause không được transaction control | Để caller sở hữu transaction |
| Không có `VOLATILE`/`STABLE`/`IMMUTABLE` | Vô nghĩa vì không nhúng vào query |

---

## 20. Bài tập thực hành (tăng dần độ khó)

- 🟢 Viết `sp_cancel_expired_orders()`: hủy các đơn `pending` quá 7 ngày, `COMMIT` sau khi xong.
- 🟢 Viết `sp_demo_txn_boundary()` như mục 3, in `txid_current()` trước/sau mỗi COMMIT để tự quan sát bằng mắt.
- 🟡 Viết `sp_bulk_apply_discount(p_category_id int, p_percent numeric)` xử lý theo lô 200 sản phẩm/lần, `RAISE NOTICE` tiến độ sau mỗi lô.
- 🟡 Viết `sp_transfer_stock` như mục 5, tạo tình huống lỗi (sản phẩm nguồn không đủ hàng) để xác nhận `ROLLBACK` hoạt động đúng, kho đích **không bị cộng nhầm**.
- 🟡 Viết procedure dùng `EXECUTE format(...)` để archive dữ liệu từ bảng thuộc allowlist, dùng `%I` cho identifier và `USING` cho điều kiện ngày.
- 🔴 Viết `sp_reconcile_daily_revenue()`: tính tổng doanh thu theo ngày, ghi vào bảng `daily_revenue_snapshot`, `COMMIT` sau mỗi ngày xử lý; test crash giữa chừng (`RAISE EXCEPTION` giả lập) để xác nhận các ngày đã COMMIT trước đó không bị mất.
- 🔴 Tạo tình huống lỗi cố ý: gọi procedure có COMMIT từ bên trong 1 FUNCTION khác, quan sát lỗi `invalid transaction termination`, rồi sửa lại đúng kiến trúc (tách COMMIT ra top-level).
- 🔴 Quan sát `FOR r IN SELECT` được chuyển thành holdable cursor ở transaction boundary đầu tiên và đo temp file khi tập kết quả lớn.
- ⚫ Lên lịch `sp_reconcile_daily_revenue()` chạy mỗi đêm qua `pg_cron`, kiểm tra `cron.job_run_details` khi cố tình cho lỗi xảy ra giữa batch.
- ⚫ Viết procedure “generic maintenance” nhận schema/bảng/cột thuộc allowlist; tuyệt đối không nhận nguyên `p_condition text`; dùng `%I` + `USING`.

Bộ đề có sẵn schema/dữ liệu và đáp án hoàn chỉnh:

- [Đề bài thực hành procedure](./postgresql-procedure-bai-tap.md)
- [Đáp án thực hành procedure](./postgresql-procedure-bai-tap-dap-an.md)

---

## 21. Tài liệu PostgreSQL chính thức

- [PL/pgSQL Transaction Management](https://www.postgresql.org/docs/15/plpgsql-transactions.html)
- [PL/pgSQL Basic Statements — assignment, `EXECUTE`, `USING`](https://www.postgresql.org/docs/current/plpgsql-statements.html)
- [`CREATE TABLE` — temporary table và `ON COMMIT`](https://www.postgresql.org/docs/current/sql-createtable.html)
- [`WITH` Queries — CTE materialization](https://www.postgresql.org/docs/current/queries-with.html)
- [`CREATE PROCEDURE` — giới hạn transaction control](https://www.postgresql.org/docs/current/sql-createprocedure.html)
