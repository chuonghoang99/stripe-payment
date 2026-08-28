# Đáp án thực hành 2 — Chuyển tồn kho an toàn

Chạy phần setup trong [đề bài](./postgresql-procedure-bai-tap.md) trước khi tạo procedure.

---

## 1. Lời giải `sp_transfer_stock`

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_2.sp_transfer_stock(
    IN p_request_id uuid,
    IN p_product_id bigint,
    IN p_from_warehouse_id bigint,
    IN p_to_warehouse_id bigint,
    IN p_quantity integer
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_product_name        text;
    v_source_name         text;
    v_destination_name    text;
    v_first_warehouse_id  bigint;
    v_second_warehouse_id bigint;
    v_first_stock         integer;
    v_second_stock        integer;
    v_source_stock        integer;
    v_destination_stock   integer;
    v_existing_transfer   record;
BEGIN
    IF p_request_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_request_id không được NULL';
    END IF;

    IF p_product_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_product_id không được NULL';
    END IF;

    IF p_from_warehouse_id IS NULL OR p_to_warehouse_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'ID kho nguồn và kho đích không được NULL';
    END IF;

    IF p_quantity IS NULL OR p_quantity NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_quantity phải nằm trong khoảng 1..1000';
    END IF;

    IF p_from_warehouse_id = p_to_warehouse_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'Kho nguồn và kho đích phải khác nhau';
    END IF;

    SELECT p.name
    INTO v_product_name
    FROM procedure_lab_2.products AS p
    WHERE p.id = p_product_id
      AND p.is_active;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Product %s không tồn tại hoặc đã ngừng hoạt động',
                p_product_id
            );
    END IF;

    SELECT w.name
    INTO v_source_name
    FROM procedure_lab_2.warehouses AS w
    WHERE w.id = p_from_warehouse_id
      AND w.is_active;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Kho nguồn %s không tồn tại hoặc đã ngừng hoạt động',
                p_from_warehouse_id
            );
    END IF;

    SELECT w.name
    INTO v_destination_name
    FROM procedure_lab_2.warehouses AS w
    WHERE w.id = p_to_warehouse_id
      AND w.is_active;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Kho đích %s không tồn tại hoặc đã ngừng hoạt động',
                p_to_warehouse_id
            );
    END IF;

    -- Unique request_id là lớp bảo vệ idempotency tại database.
    INSERT INTO procedure_lab_2.stock_transfers(
        request_id,
        product_id,
        from_warehouse_id,
        to_warehouse_id,
        quantity
    )
    VALUES (
        p_request_id,
        p_product_id,
        p_from_warehouse_id,
        p_to_warehouse_id,
        p_quantity
    )
    ON CONFLICT (request_id) DO NOTHING;

    IF NOT FOUND THEN
        SELECT st.product_id,
               st.from_warehouse_id,
               st.to_warehouse_id,
               st.quantity
        INTO v_existing_transfer
        FROM procedure_lab_2.stock_transfers AS st
        WHERE st.request_id = p_request_id;

        IF v_existing_transfer.product_id = p_product_id
           AND v_existing_transfer.from_warehouse_id = p_from_warehouse_id
           AND v_existing_transfer.to_warehouse_id = p_to_warehouse_id
           AND v_existing_transfer.quantity = p_quantity THEN
            RAISE NOTICE
                'Request % đã được xử lý trước đó; bỏ qua',
                p_request_id;
            RETURN;
        END IF;

        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = format(
                'request_id %s đã tồn tại với payload khác',
                p_request_id
            );
    END IF;

    -- Mọi transaction đều lấy hai lock theo warehouse ID tăng dần.
    v_first_warehouse_id := LEAST(
        p_from_warehouse_id,
        p_to_warehouse_id
    );
    v_second_warehouse_id := GREATEST(
        p_from_warehouse_id,
        p_to_warehouse_id
    );

    SELECT i.stock
    INTO v_first_stock
    FROM procedure_lab_2.inventory AS i
    WHERE i.product_id = p_product_id
      AND i.warehouse_id = v_first_warehouse_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Chưa có inventory cho product %s tại warehouse %s',
                p_product_id,
                v_first_warehouse_id
            );
    END IF;

    SELECT i.stock
    INTO v_second_stock
    FROM procedure_lab_2.inventory AS i
    WHERE i.product_id = p_product_id
      AND i.warehouse_id = v_second_warehouse_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Chưa có inventory cho product %s tại warehouse %s',
                p_product_id,
                v_second_warehouse_id
            );
    END IF;

    IF p_from_warehouse_id = v_first_warehouse_id THEN
        v_source_stock := v_first_stock;
        v_destination_stock := v_second_stock;
    ELSE
        v_source_stock := v_second_stock;
        v_destination_stock := v_first_stock;
    END IF;

    IF v_source_stock < p_quantity THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = format(
                'Không đủ tồn kho tại %s: còn %s, yêu cầu %s',
                v_source_name,
                v_source_stock,
                p_quantity
            );
    END IF;

    UPDATE procedure_lab_2.inventory
    SET stock = stock - p_quantity,
        updated_at = clock_timestamp()
    WHERE product_id = p_product_id
      AND warehouse_id = p_from_warehouse_id
    RETURNING stock INTO v_source_stock;

    UPDATE procedure_lab_2.inventory
    SET stock = stock + p_quantity,
        updated_at = clock_timestamp()
    WHERE product_id = p_product_id
      AND warehouse_id = p_to_warehouse_id
    RETURNING stock INTO v_destination_stock;

    RAISE NOTICE
        'Đã chuyển % % từ % sang %; tồn nguồn %, tồn đích %',
        p_quantity,
        v_product_name,
        v_source_name,
        v_destination_name,
        v_source_stock,
        v_destination_stock;
END;
$procedure$;
```

---

## 2. Vì sao transfer là atomic?

Trình tự thay đổi dữ liệu là:

```text
INSERT transfer claim
    ↓
khóa hai inventory row
    ↓
trừ stock nguồn
    ↓
cộng stock đích
```

Procedure không có `COMMIT`, `ROLLBACK` hoặc exception handler. Các bước trên chạy trong transaction của caller.

Nếu câu update kho đích lỗi, exception thoát khỏi procedure. Khi transaction rollback:

- Stock nguồn được khôi phục.
- Stock đích không thay đổi.
- Row `stock_transfers` vừa claim bị xóa theo transaction rollback.

Không có trạng thái “đã trừ nguồn nhưng chưa cộng đích” được commit.

---

## 3. Vì sao phải khóa hai row theo thứ tự cố định?

Giả sử hai request chạy đồng thời:

```text
A: Kho 1 → Kho 2
B: Kho 2 → Kho 1
```

Nếu luôn khóa nguồn trước, A có thể giữ row kho 1 trong khi B giữ row kho 2. Sau đó mỗi transaction chờ row còn lại và tạo deadlock.

Lời giải tách thứ tự lock khỏi hướng transfer:

```text
A: khóa Kho 1 → khóa Kho 2 → transfer 1 sang 2
B: khóa Kho 1 → chờ A → khóa Kho 2 → transfer 2 sang 1
```

B có thể chờ, nhưng không giữ kho 2 trong lúc chờ kho 1. Hai transaction không tạo vòng chờ lẫn nhau trong tình huống này.

`FOR UPDATE` cũng khiến hai transfer cùng lấy stock từ một nguồn được tuần tự hóa. Transaction đến sau chỉ kiểm tra stock sau khi đã nhận lock, vì vậy không thể cùng dùng giá trị stock cũ.

Mô tả trên giả định isolation mặc định `READ COMMITTED`. Ở `REPEATABLE READ` hoặc `SERIALIZABLE`, một transaction có thể nhận serialization failure thay vì tiếp tục với row mới; caller phải retry toàn bộ transaction.

> Thứ tự lock nhất quán giảm deadlock cho các object thuộc bài toán này. Ứng dụng vẫn nên retry toàn bộ transaction nếu PostgreSQL trả `deadlock_detected` hoặc serialization failure do những luồng khác trong hệ thống.

---

## 4. Vì sao idempotency không làm mất request lỗi?

Row `stock_transfers` được insert trước khi inventory thay đổi, nhưng nó chưa được commit riêng.

```text
Claim request → kiểm tra stock → lỗi không đủ hàng → transaction rollback
```

Sau rollback, request ID chưa tồn tại. Caller có thể bổ sung hàng rồi retry cùng ID.

Nếu hai session đồng thời dùng cùng một `request_id`, unique constraint chỉ cho một row được insert. Session còn lại đi vào nhánh `ON CONFLICT DO NOTHING`, đọc payload đã có và không update stock lần hai.

Việc so payload là bắt buộc. Nếu chỉ thấy request ID trùng rồi bỏ qua, một client có thể vô tình dùng cùng ID cho một product, quantity hoặc hướng chuyển khác mà không nhận được lỗi.

---

## 5. Kiểm thử nhanh

### 5.1. Happy path và retry

```sql
CALL procedure_lab_2.sp_transfer_stock(
    '11111111-1111-1111-1111-111111111111',
    1, 1, 2, 4
);

-- Gọi lại cùng payload: chỉ notice, không chuyển lần hai.
CALL procedure_lab_2.sp_transfer_stock(
    '11111111-1111-1111-1111-111111111111',
    1, 1, 2, 4
);

SELECT warehouse_id, stock
FROM procedure_lab_2.inventory
WHERE product_id = 1
  AND warehouse_id IN (1, 2)
ORDER BY warehouse_id;

SELECT count(*) AS transfer_count
FROM procedure_lab_2.stock_transfers
WHERE request_id = '11111111-1111-1111-1111-111111111111';
```

Kết quả:

```text
warehouse 1 stock = 6
warehouse 2 stock = 7
transfer_count = 1
```

### 5.2. Caller rollback

```sql
BEGIN;

CALL procedure_lab_2.sp_transfer_stock(
    '22222222-2222-2222-2222-222222222222',
    2, 1, 2, 5
);

ROLLBACK;

SELECT warehouse_id, stock
FROM procedure_lab_2.inventory
WHERE product_id = 2
ORDER BY warehouse_id;

SELECT count(*)
FROM procedure_lab_2.stock_transfers
WHERE request_id = '22222222-2222-2222-2222-222222222222';
```

Kết quả:

```text
warehouse 1 stock = 20
warehouse 2 stock = 5
count = 0
```

Tiếp tục chạy test hai session ở mục 8 và 9 của [đề bài](./postgresql-procedure-bai-tap.md).

---

## 6. Tài liệu PostgreSQL liên quan

- [Row-level locks và deadlock](https://www.postgresql.org/docs/15/explicit-locking.html)
- [`INSERT ... ON CONFLICT`](https://www.postgresql.org/docs/15/sql-insert.html#SQL-ON-CONFLICT)
- [`FOUND` trong PL/pgSQL](https://www.postgresql.org/docs/15/plpgsql-statements.html#PLPGSQL-STATEMENTS-DIAGNOSTICS)
