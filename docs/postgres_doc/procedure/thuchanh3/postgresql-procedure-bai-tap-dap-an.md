# Đáp án thực hành PostgreSQL PROCEDURE

> Chạy [file đề bài](./postgresql-procedure-bai-tap.md) trước. Hai lời giải cố ý dùng hai transaction strategy khác nhau.

---

## 1. Đáp án bài 1 — Atomic + temp table + dynamic SQL

```sql
CREATE OR REPLACE PROCEDURE procedure_lab.sp_discount_top_categories(
    IN p_run_id uuid,
    IN p_top_n integer,
    IN p_discount_percent numeric,
    IN p_rank_by text DEFAULT 'inventory_value',
    IN p_price_column text DEFAULT 'price'
)
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp, procedure_lab
AS $procedure$
DECLARE
    v_sql          text;
    v_category_id  integer;
    v_product      record;
    v_new_price    numeric(14, 2);
BEGIN
    IF p_run_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_run_id không được NULL';
    END IF;

    IF p_top_n IS NULL OR p_top_n NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_top_n phải nằm trong khoảng 1..100';
    END IF;

    IF p_discount_percent IS NULL
       OR p_discount_percent <= 0
       OR p_discount_percent >= 100 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_discount_percent phải lớn hơn 0 và nhỏ hơn 100';
    END IF;

    IF p_rank_by IS NULL
       OR p_rank_by NOT IN ('product_count', 'total_stock', 'inventory_value') THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = format('p_rank_by không hợp lệ: %s',
                             COALESCE(p_rank_by, '<NULL>'));
    END IF;

    IF p_price_column IS NULL
       OR p_price_column NOT IN ('price', 'sale_price') THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = format('p_price_column không hợp lệ: %s',
                             COALESCE(p_price_column, '<NULL>'));
    END IF;

    INSERT INTO procedure_lab.procedure_runs(
        run_id, procedure_name, status
    )
    VALUES (
        p_run_id, 'sp_discount_top_categories', 'running'
    )
    ON CONFLICT (run_id, procedure_name) DO NOTHING;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = format('run_id %s đã được sử dụng cho procedure này',
                             p_run_id);
    END IF;

    DROP TABLE IF EXISTS pg_temp.temp_top_categories;

    -- p_rank_by đã qua allowlist và được quote bằng %I.
    -- p_top_n có kiểu integer và đã validate trước khi dùng với %s.
    v_sql := format(
        $sql$
        CREATE TEMP TABLE temp_top_categories
        ON COMMIT DROP
        AS
        SELECT p.category_id,
               COUNT(*)::bigint AS product_count,
               SUM(p.stock)::bigint AS total_stock,
               SUM(p.price * p.stock)::numeric AS inventory_value
        FROM procedure_lab.products AS p
        WHERE p.is_active
        GROUP BY p.category_id
        ORDER BY %I DESC, p.category_id
        LIMIT %s
        $sql$,
        p_rank_by,
        p_top_n
    );

    EXECUTE v_sql;

    CREATE INDEX ON temp_top_categories(category_id);
    ANALYZE temp_top_categories;

    FOR v_category_id IN
        SELECT t.category_id
        FROM temp_top_categories AS t
        ORDER BY t.category_id
    LOOP
        -- Identifier phải đi qua format(%I); category ID là value nên dùng $1/USING.
        FOR v_product IN EXECUTE format(
            $sql$
            SELECT p.id,
                   p.category_id,
                   COALESCE(p.%1$I, p.price)::numeric AS old_price
            FROM procedure_lab.products AS p
            WHERE p.category_id = $1
              AND p.is_active
              AND p.stock > 0
            ORDER BY p.id
            $sql$,
            p_price_column
        ) USING v_category_id
        LOOP
            v_new_price := round(
                v_product.old_price * (1 - p_discount_percent / 100),
                2
            );

            EXECUTE format(
                $sql$
                UPDATE procedure_lab.products
                SET %I = $1,
                    updated_at = clock_timestamp()
                WHERE id = $2
                $sql$,
                p_price_column
            ) USING v_new_price, v_product.id;

            INSERT INTO procedure_lab.price_change_audit(
                run_id,
                product_id,
                category_id,
                price_column,
                old_price,
                new_price
            )
            VALUES (
                p_run_id,
                v_product.id,
                v_product.category_id,
                p_price_column,
                v_product.old_price,
                v_new_price
            );
        END LOOP;
    END LOOP;

    UPDATE procedure_lab.procedure_runs
    SET status = 'success',
        finished_at = clock_timestamp()
    WHERE run_id = p_run_id
      AND procedure_name = 'sp_discount_top_categories';

    -- Không COMMIT ở đây. Toàn bộ procedure là một transaction atomic.
    -- temp_top_categories tự drop khi caller commit transaction.
END;
$procedure$;
```

### Vì sao chống được SQL injection?

- `p_rank_by` và `p_price_column` phải thuộc allowlist trước khi ghép query.
- `%I` quote identifier; input không thể thoát ra thành một câu SQL thứ hai.
- `v_category_id`, `v_new_price`, `v_product.id` là value nên dùng `$1`, `$2` và `USING`.
- `p_top_n` đã được PostgreSQL parse thành `integer`, sau đó được validate; nó không thể chứa payload dạng text.
- Tên schema/bảng được viết cố định, không phụ thuộc `search_path`.

`SET search_path` trên định nghĩa làm procedure không được transaction control. Đây không phải hạn chế trong bài này vì mục tiêu là all-or-nothing và caller sở hữu transaction.

### Kết quả chính cần thấy

Sau lời gọi đầu tiên trong đề:

```text
audit_rows = 4
Laptop      18000000.00
Chuột          450000.00
Nồi chiên     2700000.00
Máy hút bụi   3600000.00
```

Product inactive không đổi. `to_regclass('pg_temp.temp_top_categories')` trả `NULL` sau khi statement `CALL` được autocommit. Nếu `CALL` nằm trong transaction thủ công, temp table chỉ bị drop khi transaction đó `COMMIT`.

---

## 2. Đáp án bài 2 — Commit từng category + resume

Lời giải bảo đảm không xử lý lặp khi **gọi tuần tự** cùng `run_id`. Bài lab giả định một worker cho mỗi `run_id`; hai session chạy đồng thời cùng `run_id` vẫn có thể cùng chụp một category trước khi log `success` được commit. Production cần thêm cơ chế claim/lease hoặc khóa phù hợp.

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
    IF p_run_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_run_id không được NULL';
    END IF;

    IF p_stock_increment IS NULL
       OR p_stock_increment NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_stock_increment phải nằm trong khoảng 1..1000';
    END IF;

    INSERT INTO procedure_lab.procedure_runs(
        run_id, procedure_name, status, finished_at
    )
    VALUES (
        p_run_id, 'sp_restock_categories_batch', 'running', NULL
    )
    ON CONFLICT (run_id, procedure_name) DO UPDATE
    SET status = 'running',
        finished_at = NULL;

    -- Chụp danh sách trước transaction boundary đầu tiên.
    -- Khi resume, category success bị loại; category failed được retry.
    SELECT COALESCE(array_agg(c.id ORDER BY c.id), ARRAY[]::integer[])
    INTO v_category_ids
    FROM procedure_lab.categories AS c
    WHERE c.is_active
      AND NOT EXISTS (
          SELECT 1
          FROM procedure_lab.category_job_log AS l
          WHERE l.run_id = p_run_id
            AND l.job_name = 'restock_categories'
            AND l.category_id = c.id
            AND l.status = 'success'
      );

    FOREACH v_category_id IN ARRAY v_category_ids LOOP
        v_affected_rows := 0;
        v_failed := false;
        v_sqlstate := NULL;
        v_message := NULL;

        BEGIN
            UPDATE procedure_lab.products
            SET stock = stock + p_stock_increment,
                updated_at = clock_timestamp()
            WHERE category_id = v_category_id
              AND is_active;

            GET DIAGNOSTICS v_affected_rows = ROW_COUNT;

            IF v_affected_rows = 0 THEN
                RAISE EXCEPTION 'Category % không có product active',
                                v_category_id;
            END IF;

            IF v_category_id = p_fail_category_id THEN
                RAISE EXCEPTION 'Lỗi giả lập sau UPDATE cho category %',
                                v_category_id;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            -- Inner block là subtransaction: UPDATE phía trên đã tự rollback.
            -- Không được đặt ROLLBACK ở đây vì exception handler còn active.
            v_failed := true;
            v_affected_rows := 0;

            GET STACKED DIAGNOSTICS
                v_sqlstate = RETURNED_SQLSTATE,
                v_message = MESSAGE_TEXT;
        END;

        INSERT INTO procedure_lab.category_job_log(
            run_id,
            job_name,
            category_id,
            status,
            affected_rows,
            sqlstate,
            message
        )
        VALUES (
            p_run_id,
            'restock_categories',
            v_category_id,
            CASE WHEN v_failed THEN 'failed' ELSE 'success' END,
            v_affected_rows,
            v_sqlstate,
            v_message
        )
        ON CONFLICT (run_id, job_name, category_id) DO UPDATE
        SET status = EXCLUDED.status,
            affected_rows = EXCLUDED.affected_rows,
            sqlstate = EXCLUDED.sqlstate,
            message = EXCLUDED.message,
            updated_at = clock_timestamp();

        -- Đã ra khỏi block có EXCEPTION nên transaction control hợp lệ.
        -- Log và thay đổi stock của một category được commit cùng nhau.
        COMMIT AND CHAIN;
    END LOOP;

    SELECT EXISTS (
        SELECT 1
        FROM procedure_lab.category_job_log AS l
        WHERE l.run_id = p_run_id
          AND l.job_name = 'restock_categories'
          AND l.status = 'failed'
    )
    INTO v_has_errors;

    UPDATE procedure_lab.procedure_runs
    SET status = CASE
                     WHEN v_has_errors THEN 'completed_with_errors'
                     ELSE 'success'
                 END,
        finished_at = clock_timestamp()
    WHERE run_id = p_run_id
      AND procedure_name = 'sp_restock_categories_batch';

    COMMIT;
END;
$procedure$;
```

### Trạng thái sau lần chạy có lỗi giả lập

Với `p_fail_category_id = 2`:

- Category 1, 3, 4: stock tăng `5`, log `success` đã commit.
- Category 2: exception xảy ra sau `UPDATE`, nhưng inner subtransaction tự rollback; stock không đổi và log là `failed`.
- Run có trạng thái `completed_with_errors`.

Gọi lại cùng `run_id` với `p_fail_category_id = NULL` chỉ đưa category 2 vào mảng. Sau lần hai, cả bốn log là `success`; category 1, 3, 4 không bị tăng lần nữa.

### Tại sao không dùng `ON COMMIT DROP` trong bài 2?

Procedure commit sau mỗi category. Nếu danh sách điều khiển nằm trong temp table `ON COMMIT DROP`, bảng sẽ biến mất sau category đầu tiên. Lời giải chụp ID vào mảng PL/pgSQL trước vòng lặp; mảng tiếp tục tồn tại khi procedure chuyển qua transaction mới.

Nếu danh sách quá lớn để giữ trong mảng, có thể dùng temp table `ON COMMIT PRESERVE ROWS`, thêm khóa/index cần thiết, rồi `DROP TABLE` tường minh khi job hoàn tất.

---

## 3. Lệnh kiểm tra bổ sung

### 3.1. Chứng minh bài 1 atomic

Chạy lại setup, tạo procedure bài 1, rồi cố ý làm audit lỗi bằng một constraint tạm thời:

```sql
ALTER TABLE procedure_lab.price_change_audit
ADD CONSTRAINT reject_large_new_price CHECK (new_price < 10000000);

CALL procedure_lab.sp_discount_top_categories(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    2,
    10,
    'inventory_value',
    'price'
);

-- CALL lỗi: price, audit và procedure_runs đều phải quay về trạng thái trước CALL.
SELECT *
FROM procedure_lab.procedure_runs
WHERE run_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';

ALTER TABLE procedure_lab.price_change_audit
DROP CONSTRAINT reject_large_new_price;
```

Nếu chạy trong `psql` với autocommit bật, câu `ALTER ... DROP CONSTRAINT` sau lỗi vẫn chạy được. Nếu client đang dùng transaction thủ công, phải `ROLLBACK` transaction lỗi trước.

### 3.2. Chứng minh transaction control phải ở top-level

```sql
BEGIN;

CALL procedure_lab.sp_restock_categories_batch(
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    1,
    NULL
);

-- ERROR: invalid transaction termination
ROLLBACK;
```

Gọi procedure bài 2 như một top-level `CALL`, không đặt trong `BEGIN ... COMMIT` và không gọi từ Spring service có `@Transactional`.
