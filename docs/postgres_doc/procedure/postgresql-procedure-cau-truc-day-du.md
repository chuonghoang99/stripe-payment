# Cấu trúc đầy đủ của một PROCEDURE — kết hợp nhiều kỹ thuật

> Tài liệu này gộp **toàn bộ** kỹ thuật thường dùng trong một PROCEDURE và chỉ ra vị trí mà mỗi kỹ thuật nằm trong tổng thể. Ví dụ xuyên suốt là một **batch xử lý đơn hàng** vừa tự commit từng phần, vừa chống lỗi từng dòng, vừa cho phép tùy chỉnh tiêu chí xử lý bằng dynamic SQL.
>
> Nội dung lý thuyết từng kỹ thuật (chi tiết + giới hạn) nằm ở [PROCEDURE trong PostgreSQL — Chi tiết đầy đủ](postgresql-procedure-chi-tiet.md).

---

## 1. Danh mục kỹ thuật được đưa vào bài này

| # | Kỹ thuật | Dùng để làm gì trong procedure |
|---|---|---|
| 1 | `FOUND` | Kiểm tra một `SELECT`/`UPDATE`/`INSERT ... RETURNING` có trả/đụng row hay không |
| 2 | `EXCEPTION` block | Bắt lỗi **từng dòng**, cô lập phần lỗi mà không hỏng cả batch |
| 3 | Savepoint | Nested block tạo subtransaction; `ROLLBACK TO` chỉ hoàn tác một phần |
| 4 | Dynamic SQL | `EXECUTE ... USING ...` với tên cột/giá trị an toàn |
| 5 | CTE (`WITH ...`) | Gom/nhóm dữ liệu cần xử lý trước khi insert vào temp table |
| 6 | `COMMIT` / `ROLLBACK` | Tự kiểm soát transaction từng phần trong một lần `CALL` |
| 7 | TEMP TABLE | Staging dữ liệu, `ON COMMIT DROP` tự dọn khi transaction kết thúc |

---

## 2. Sơ đồ tổng thể — từng kỹ thuật nằm ở đâu

```text
CREATE PROCEDURE
  │
  ├─ (A) Validate input ------------------------------------- FOUND/Exception
  ├─ (B) Taạo temp table staging --------------------------- TEMP TABLE (ON COMMIT DROP)
  ├─ (C) Dựng query bằng CTE + dynamic SQL ------------------ CTE + EXECUTE
  ├─ (D) Insert INTO staging  ... SELECT (CTE) -------------- CTE + INSERT INTO ... SELECT
  ├─ Lặp từng dòng staging:
  │      ├─ BEGIN ... EXCEPTION ... END  (subtransaction) -- EXCEPTION
  │      │      ├─ SAVEPOINT  ------------------------------ Savepoint
  │      │      ├─ UPDATE ... check FOUND ------------------ FOUND
  │      │      └─ EXECUTE dynamic UPDATE ban ghi ---------- Dynamic SQL
  │      ├─ IF lỗi: INSERT log + ROLLBACK TO SAVEPOINT ----- Savepoint
  │      └─ END;  (ra khỏi block có EXCEPTION)
  │        └─ COMMIT AND CHAIN  ---------------------------- COMMIT
  ├─ Xử lý trường hợp "không có gì để làm" ------------------ FOUND
  └─ Dọn dẹp / kết thúc -------------------------------------- ROLLBACK / COMMIT
```

---

## 3. Bảng và dữ liệu mẫu

Chạy trong `psql` (schema `procedure_full`):

```sql
DROP SCHEMA IF EXISTS procedure_full CASCADE;
CREATE SCHEMA procedure_full;

CREATE TABLE procedure_full.orders (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   bigint NOT NULL,
    total         numeric(14, 2) NOT NULL DEFAULT 0,
    status        text NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'processing', 'paid', 'failed')),
    applied_at    timestamptz
);

CREATE TABLE procedure_full.process_log (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      bigint,
    action        text NOT NULL,
    sqlstate      text,
    message       text
);

INSERT INTO procedure_full.orders(customer_id, total, status)
VALUES
    (1, 100, 'pending'),
    (2, 200, 'pending'),
    (3, 300, 'pending'),
    (4, 400, 'pending'),
    (5,  -50, 'pending');  -- total âm: sẽ bị từ chối bởi dynamic SQL
```

---

## 4. Procedure đầy đủ

```sql
CREATE OR REPLACE PROCEDURE procedure_full.sp_process_orders_batch(
    IN  p_batch_size     integer DEFAULT 1000,
    IN  p_table_prefix   text    DEFAULT 'stg',       -- tiền tố bảng staging
    OUT o_processed      integer,                     -- số đơn thành công
    OUT o_failed         integer                      -- số đơn lỗi
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    -- Tên có thể thay đổi được → không nhúng cứng vào câu lệnh tĩnh.
    v_stage_table   text;
    -- Các cột mà "tiêu chí xử lý" có thể thay đổi động.
    v_status_col    text := 'status';
    v_min_total     numeric := 0;

    v_rec           record;
    v_batch_count   integer;

    v_effected_rows integer;
    v_sqlstate      text;
    v_message       text;

    -- Đếm tạm trong vòng lặp.
    v_done          integer := 0;
    v_bad           integer := 0;
BEGIN
    -----------------------------------
    -- (A) Validate input
    -----------------------------------
    IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_batch_size phải là số dương';
    END IF;

    IF btrim(p_table_prefix) = '' THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_table_prefix không được rỗng';
    END IF;

    -----------------------------------
    -- (B) Tạo temp table staging
    --     ON COMMIT DROP: tự hủy khi transaction kết thúc.
    -----------------------------------
    v_stage_table := format('%s_orders', btrim(p_table_prefix));

    EXECUTE format(
        'CREATE TEMP TABLE %I (order_id bigint PRIMARY KEY) ON COMMIT DROP',
        v_stage_table
    );

    -----------------------------------
    -- (C) + (D): dùng CTE để chọn đơn, rồi chèn vào staging.
    --     Tên cột tiêu chí được đưa vào an toàn bằng %I (identifier),
    --     giá trị ngưỡng đưa vào bằng %L (literal) — tránh SQL injection.
    -----------------------------------
    EXECUTE format(
        'WITH candidates AS (
             SELECT id
             FROM procedure_full.orders
             WHERE %I = ''pending''
               AND total >= %L
         )
         INSERT INTO %I (order_id)
         SELECT id FROM candidates',
        v_status_col,
        v_min_total,
        v_stage_table
    );

    -----------------------------------
    -- (E) Đếm số row thực sự cần xử lý.
    -----------------------------------
    EXECUTE format('SELECT count(*) FROM %I', v_stage_table)
        INTO v_batch_count;

    -- Sử dụng FOUND ngay sau câu lệnh: không có gì cần xử lý.
    IF NOT FOUND OR v_batch_count = 0 THEN
        RAISE NOTICE 'Không có đơn pending nào để xử lý';
        o_processed := 0;
        o_failed    := 0;
        RETURN;
    END IF;

    RAISE NOTICE 'Bắt đầu xử lý % đơn', v_batch_count;

    -----------------------------------
    -- (F) Lặp từng đơn trong staging.
    --     Mỗi vòng là một block có EXCEPTION → subtransaction riêng.
    -----------------------------------
    FOR v_rec IN
        EXECUTE format('SELECT order_id FROM %I ORDER BY order_id', v_stage_table)
    LOOP
        -- Reset cờ trạng thái cho vòng lặp.
        v_sqlstate := NULL;
        v_message  := NULL;

        BEGIN
            -- SAVEPOINT: cho phép hoàn tác riêng những thay đổi
            -- của vòng này (nếu có) về sau.
            SAVEPOINT sp_before_order;

            -----------------------------------
            -- (G) Cập nhật đơn theo trạng thái.
            --     FOUND dùng ngay sau UPDATE để biết có đơn nào đổi state.
            -----------------------------------
            UPDATE procedure_full.orders
            SET status = 'processing'
            WHERE id = v_rec.order_id
              AND status = 'pending';

            IF NOT FOUND THEN
                RAISE EXCEPTION 'Đơn % không còn ở trạng thái pending', v_rec.order_id;
            END IF;

            -----------------------------------
            -- (H) Dynamic SQL cập nhật trạng thái cuối theo tên cột động.
            --     %I cho cột, %L cho giá trị, :id truyền bằng USING (value).
            -----------------------------------
            EXECUTE format(
                'UPDATE procedure_full.orders
                 SET status = ''paid'', applied_at = clock_timestamp()
                 WHERE id = :id AND status = ''processing'' RETURNING id',
                v_status_col   -- không dùng thật, minh họa chỗ đặt identifier động
            )
            USING v_rec.order_id
            INTO v_effected_rows;

            IF NOT FOUND THEN
                RAISE EXCEPTION 'Đơn % không thể chuyển sang paid', v_rec.order_id;
            END IF;

            -- Ghi log thành công.
            INSERT INTO procedure_full.process_log(order_id, action)
            VALUES (v_rec.order_id, 'paid');

            v_done := v_done + 1;

        EXCEPTION WHEN OTHERS THEN
            -- Toàn bộ thay đổi của block này đã bị hoàn tác tự động.
            -- Nếu đã có SAVEPOINT, ta còn chủ động cuộn về điểm trước vòng.
            ROLLBACK TO SAVEPOINT sp_before_order;

            v_bad := v_bad + 1;
            GET STACKED DIAGNOSTICS
                v_sqlstate = RETURNED_SQLSTATE,
                v_message  = MESSAGE_TEXT;

            -- Ghi lỗi vào log (không bị rollback vì nằm sau ROLLBACK TO).
            INSERT INTO procedure_full.process_log(order_id, action, sqlstate, message)
            VALUES (v_rec.order_id, 'failed', v_sqlstate, v_message);
        END;

        -- Đã ra khỏi block có EXCEPTION → đặt transaction boundary ở đây.
        -- COMMIT AND CHAIN: chốt lô này rồi mở transaction mới cùng isolation.
        COMMIT AND CHAIN;
    END LOOP;

    -----------------------------------
    -- (I) Kết thúc: gán output + dọn dẹp.
    -----------------------------------
    o_processed := v_done;
    o_failed    := v_bad;

    RAISE NOTICE 'Xong: % thành công, % lỗi', o_processed, o_failed;
END;
$procedure$;
```

---

## 5. Gọi procedure và kiểm kết quả

```sql
CALL procedure_full.sp_process_orders_batch(
    p_batch_size   := 1000,
    p_table_prefix := 'stg',
    o_processed    := NULL,
    o_failed       := NULL
);
```

Kiểm tra sau khi chạy:

```sql
SELECT id, total, status, applied_at
FROM procedure_full.orders
ORDER BY id;

SELECT order_id, action, sqlstate, message
FROM procedure_full.process_log
ORDER BY id;
```

Kết quả kỳ vọng:

- Các đơn `total >= 0` và `pending` → `paid`, có `applied_at`.
- Đơn `id = 5` có `total = -50` → bị từ chối bởi status check trong dynamic SQL; vòng lặp bắt lỗi, ghi `failed` vào log, **các đơn khác vẫn xử lý được**.
- `o_processed = 4`, `o_failed = 1`.
- Temp table `stg_orders` **không còn tồn tại** sau khi `CALL` kết thúc (do `ON COMMIT DROP`).

---

## 6. Giải thích từng kỹ thuật tại vị trí được dùng

### 6.1. `FOUND` — kiểm tra "có row không"

Dùng ba lần trong bài:

1. Sau `SELECT count(*) ... INTO` để biết staging rỗng hay không (xem mục 4E).
2. Sau `UPDATE ... WHERE status = 'pending'` để phát hiện đơn đã bị worker khác chiếm (mục 4G).
3. Sau `UPDATE ... RETURNING id INTO` để chắc chắn đơn đã chuyển thành `paid` (mục 4H).

> Quy tắc: `FOUND` phản ánh **câu lệnh gần nhất**. Phải kiểm tra ngay lập tức, không chèn câu lệnh khác vào giữa.

### 6.2. `EXCEPTION` block — cô lập lỗi từng dòng

Mỗi `BEGIN ... EXCEPTION ... END` là một subtransaction. Khi lỗi trong block xảy ra, các `INSERT`/`UPDATE` của riêng block đó bị hoàn tác, nhưng lỗi không làm hỏng toàn bộ procedure. Vòng lặp tiếp tục với đơn kế tiếp.

### 6.3. Savepoint — hoàn tác một phần có chủ đích

`SAVEPOINT sp_before_order;` đánh dấu điểm đầu vòng lặp. `ROLLBACK TO SAVEPOINT sp_before_order;` trong handler chỉ cuộn về điểm đó. Đây là cách kiểm soát mịn hơn subtransaction mặc định. Lưu ý: vì block có `EXCEPTION` đã tự rollback nội bộ, `ROLLBACK TO SAVEPOINT` ở đây chủ yếu minh họa kỹ thuật — trong thực tế thường đặt savepoint ở các branch `IF/ELSE` để chọn hoàn tác một phần thay vì toàn bộ.

### 6.4. Dynamic SQL — `EXECUTE ... USING`

Tên temp table và cột tiêu chí không nhúng cứng được vì phụ thuộc tham số:

```sql
EXECUTE format('CREATE TEMP TABLE %I ...', v_stage_table);
EXECUTE format('SELECT ... FROM %I', v_stage_table) INTO v_batch_count;
EXECUTE format('UPDATE ... WHERE id = :id ...', ...) USING v_rec.order_id;
```

- `%I` (identifier) dùng cho **tên bảng/cột**.
- `%L` (literal) dùng cho **giá trị**.
- `USING` / `:id` dùng cho **tham số value** — không nối chuỗi.

> Vẫn nên giữ một allowlist để giới hạn object nghiệp vụ được phép; `%I` chống SQL injection chứ không phải là "bảo mật bằng cách ẩn".

### 6.5. CTE — gom dữ liệu trước khi staging

CTE `candidates` trong lệnh `INSERT INTO ... SELECT` giúp:

- Lọc đơn hợp lệ trong **một câu lệnh duy nhất**.
- Kết hợp với tên cột/ngưỡng động.
- Chỉ chèn đúng những dòng cần xử lý vào temp table.

### 6.6. `COMMIT` / `ROLLBACK` trong PROCEDURE

- `COMMIT AND CHAIN` đặt **sau khi ra khỏi** block có `EXCEPTION` → hợp lệ, và cứ thế chốt từng đơn thành một transaction riêng; `AND CHAIN` giữ nguyên isolation level cho transaction kế.
- Trong handler, dùng `ROLLBACK TO SAVEPOINT` thay vì `ROLLBACK` toàn cục để không hủy toàn bộ batch.
- Không đặt `COMMIT`/`ROLLBACK` bên trong một block có `EXCEPTION` — PostgreSQL cấm điều này.

### 6.7. TEMP TABLE — staging tạm thời

```sql
CREATE TEMP TABLE ... ON COMMIT DROP;
```

- Riêng cho phiên/caller, tên nằm trong schema `pg_temp`.
- Giảm số lần truy cập table lớn so với query lại nhiều lần.
- `ON COMMIT DROP` tự xóa khi transaction kết thúc → không cần thủ công `DROP`.

---

## 7. Bảng kỹ thuật — so sánh nhanh vị trí sử dụng

| Kỹ thuật | Dòng làm gì | Tại sao bắt buộc/chủ động dùng |
|---|---|---|
| `FOUND` | Kiểm tra staging rỗng, cập nhật state, chuyển `paid` | Báo đúng trạng thái cạnh tranh khi worker khác chạy đè |
| `EXCEPTION` | Bọc từng vòng lặp | Giữ batch chạy tiếp dù một đơn lỗi |
| `SAVEPOINT` + `ROLLBACK TO` | Đánh dấu & cuộn về đầu vòng | Hoàn tác có chủ đích một phần |
| Dynamic SQL | Tên bảng/cột, ngưỡng lọc theo tham số | Không nhúng cứng được vì phụ thuộc tham số |
| CTE | Chọn `candidates` rồi chèn staging | Gộp lọc + chèn trong một câu lệnh |
| `COMMIT AND CHAIN` | Chốt từng đơn | PROCEDURE mới được tự commit giữa chừng |
| TEMP TABLE | Staging + `ON COMMIT DROP` | Tối ưu nhiều lần đọc, tự dọn |

---

## 8. Những cạm bẫy khi ghép chung các kỹ thuật

1. **Không `COMMIT` bên trong block có `EXCEPTION`.** Đặt transaction boundary ở sau `END;` của block.
2. **`FOUND` cần đọc ngay sau câu lệnh.** Giữa `UPDATE` và `IF NOT FOUND` không được chèn `RAISE NOTICE`/lệnh khác.
3. **`ROLLBACK TO SAVEPOINT` không xóa savepoint.** Muốn dùng lại cùng tên trong vòng sau thì để nó bị override, hoặc `RELEASE SAVEPOINT` khi không cần.
4. **Temp table và dynamic SQL phải tính cả transaction èm.** Với `ON COMMIT DROP`, nếu `CALL` lỗi và caller rollback, staging cũng biến mất — đừng tham chiếu nó ở lần gọi sau.
5. **Set `search_path` cẩn thận.** `CREATE TEMP TABLE` ưu tiên schema `pg_temp`; tránh nhầm với bảng cùng tên trong schema nghiệp vụ.
6. **Khi có cả dynamic SQL lẫn exception**, hãy dùng `GET STACKED DIAGNOSTICS` cùng `RETURNED_SQLSTATE`/`MESSAGE_TEXT` để log đúng lỗi của câu lệnh động.

---

## 9. Tài liệu liên quan

- [PROCEDURE trong PostgreSQL — Chi tiết đầy đủ](postgresql-procedure-chi-tiet.md)
- [Thực hành 1 — kỹ thuật cơ bản (FOUND, FOR UPDATE, RETURNING)](thuchanh1/1-postgresql-procedure-bai-tap.md)
- [Thực hành 3 — dynamic SQL, temp table, batch commit](thuchanh3/postgresql-procedure-bai-tap.md)
