# Đáp án thực hành 1 — Phiên bản sử dụng `EXCEPTION`

Chạy phần tạo bảng và dữ liệu trong [đề bài](1-postgresql-procedure-bai-tap.md) trước khi tạo procedure.

Đây là **đáp án thay thế để học exception handling**. Đáp án gốc cố ý để exception truyền thẳng về caller; phiên bản này dùng inner block `BEGIN ... EXCEPTION ... END` để:

- Chuyển `NO_DATA_FOUND` thành thông báo nghiệp vụ rõ ràng.
- Lấy thông tin lỗi constraint bằng `GET STACKED DIAGNOSTICS`.
- Chứng minh thay đổi trong inner block tự rollback trước khi handler chạy.
- Ném lỗi trở lại caller, không nuốt lỗi.

Không đặt `COMMIT`, `ROLLBACK`, `COMMIT AND CHAIN` hoặc `ROLLBACK AND CHAIN` trong block có `EXCEPTION`.

---

## 1. Bài 1 — `sp_create_order` sử dụng exception

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_1.sp_create_order(
    IN p_product_id bigint,
    IN p_quantity integer
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_product_name    text;
    v_unit_price      numeric(14, 2);
    v_current_stock   integer;
    v_remaining_stock integer;
    v_order_id        bigint;
    v_constraint_name text;
    v_error_detail    text;
BEGIN
    IF p_product_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_product_id không được NULL';
    END IF;

    IF p_quantity IS NULL OR p_quantity NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_quantity phải nằm trong khoảng 1..100';
    END IF;

    BEGIN
        -- STRICT phát sinh NO_DATA_FOUND nếu product không tồn tại
        -- hoặc không active. FOR UPDATE vẫn chống overselling.
        SELECT p.name,
               p.price,
               p.stock
        INTO STRICT
             v_product_name,
             v_unit_price,
             v_current_stock
        FROM procedure_lab_1.products AS p
        WHERE p.id = p_product_id
          AND p.is_active
        FOR UPDATE;

        IF v_current_stock < p_quantity THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = format(
                    'Không đủ tồn kho cho %s: còn %s, yêu cầu %s',
                    v_product_name,
                    v_current_stock,
                    p_quantity
                );
        END IF;

        v_remaining_stock := v_current_stock - p_quantity;

        UPDATE procedure_lab_1.products
        SET stock = v_remaining_stock,
            updated_at = clock_timestamp()
        WHERE id = p_product_id;

        INSERT INTO procedure_lab_1.orders(
            product_id,
            quantity,
            unit_price,
            total_amount,
            status
        )
        VALUES (
            p_product_id,
            p_quantity,
            v_unit_price,
            v_unit_price * p_quantity,
            'created'
        )
        RETURNING id INTO v_order_id;

    EXCEPTION
        WHEN no_data_found THEN
            -- Inner subtransaction đã rollback trước khi vào đây.
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format(
                    'Product %s không tồn tại hoặc đã ngừng bán',
                    p_product_id
                );

        WHEN check_violation THEN
            -- Ví dụ: CHECK stock >= 0 hoặc constraint của orders lỗi.
            GET STACKED DIAGNOSTICS
                v_constraint_name = CONSTRAINT_NAME,
                v_error_detail = PG_EXCEPTION_DETAIL;

            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = format(
                    'Không thể tạo order do vi phạm constraint %s',
                    COALESCE(v_constraint_name, '<không xác định>')
                ),
                DETAIL = COALESCE(v_error_detail, 'Không có thông tin chi tiết');
    END;

    RAISE NOTICE
        'Đã tạo order % cho %, số lượng %, còn lại %',
        v_order_id,
        v_product_name,
        p_quantity,
        v_remaining_stock;
END;
$procedure$;
```

### 1.1. Vì sao không bắt `WHEN OTHERS`?

Procedure chỉ xử lý những lỗi mà nó hiểu và có thể chuyển thành thông báo rõ hơn:

- `NO_DATA_FOUND`: product không tồn tại hoặc inactive.
- `CHECK_VIOLATION`: một invariant của database bị vi phạm.

Lỗi “không đủ tồn kho” dùng SQLSTATE `P0001`. Vì không có handler tương ứng, lỗi tự truyền ra caller. Các lỗi không dự kiến khác cũng truyền ra ngoài, tránh tình trạng procedure báo thành công giả.

### 1.2. Vì sao vẫn chống được race condition?

`SELECT ... FOR UPDATE` nằm trong inner block và khóa row product. Lock được giữ cho đến khi transaction kết thúc.

- Nếu inner block thành công, lock tiếp tục được giữ đến khi caller commit hoặc rollback.
- Nếu inner block phát sinh lỗi, subtransaction rollback và giải phóng những lock được lấy bên trong block trước khi handler chạy.
- Hai transaction thành công không thể cùng đọc một giá trị stock cũ.

---

## 2. Test rollback khi lỗi xảy ra sau `UPDATE stock`

Test này thêm tạm một constraint để ép `INSERT orders` lỗi sau khi stock đã được update.

Chạy ở chế độ autocommit:

```sql
DELETE FROM procedure_lab_1.orders WHERE product_id = 2;

UPDATE procedure_lab_1.products
SET stock = 20,
    updated_at = clock_timestamp()
WHERE id = 2;

ALTER TABLE procedure_lab_1.orders
ADD CONSTRAINT reject_mouse_order_for_exception_demo
CHECK (product_id <> 2);

CALL procedure_lab_1.sp_create_order(2, 3);
```

`CALL` phải báo `check_violation`. Kiểm tra:

```sql
SELECT stock
FROM procedure_lab_1.products
WHERE id = 2;

SELECT count(*) AS mouse_order_count
FROM procedure_lab_1.orders
WHERE product_id = 2;
```

Kết quả bắt buộc:

```text
stock = 20
mouse_order_count = 0
```

Mặc dù câu `UPDATE products` đã chạy trước `INSERT orders`, inner subtransaction đã rollback câu update khi insert vi phạm constraint.

Dọn constraint test:

```sql
ALTER TABLE procedure_lab_1.orders
DROP CONSTRAINT reject_mouse_order_for_exception_demo;
```

> Nếu chạy test trong một transaction thủ công, exception làm transaction ngoài chuyển sang trạng thái aborted. Khi đó phải `ROLLBACK` trước khi chạy lệnh dọn constraint.

---

## 3. Bài 2 — `sp_cancel_order` sử dụng exception

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_1.sp_cancel_order(
    IN p_order_id bigint
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_product_id     bigint;
    v_quantity       integer;
    v_order_status   text;
    v_product_name   text;
    v_new_stock      integer;
    v_constraint_name text;
    v_error_detail    text;
BEGIN
    IF p_order_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_order_id không được NULL';
    END IF;

    BEGIN
        -- Khóa order trước khi kiểm tra status để hai session
        -- không thể hoàn stock cho cùng order hai lần.
        SELECT o.product_id,
               o.quantity,
               o.status
        INTO STRICT
             v_product_id,
             v_quantity,
             v_order_status
        FROM procedure_lab_1.orders AS o
        WHERE o.id = p_order_id
        FOR UPDATE;

        IF v_order_status <> 'created' THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = format(
                    'Order %s không thể hủy vì đang ở trạng thái %s',
                    p_order_id,
                    v_order_status
                );
        END IF;

        UPDATE procedure_lab_1.orders
        SET status = 'cancelled'
        WHERE id = p_order_id;

        UPDATE procedure_lab_1.products
        SET stock = stock + v_quantity,
            updated_at = clock_timestamp()
        WHERE id = v_product_id
        RETURNING name, stock
        INTO v_product_name, v_new_stock;

        IF NOT FOUND THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format(
                    'Product %s của order %s không tồn tại',
                    v_product_id,
                    p_order_id
                );
        END IF;

    EXCEPTION
        WHEN no_data_found THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format('Order %s không tồn tại', p_order_id);

        WHEN check_violation THEN
            GET STACKED DIAGNOSTICS
                v_constraint_name = CONSTRAINT_NAME,
                v_error_detail = PG_EXCEPTION_DETAIL;

            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = format(
                    'Không thể hoàn tồn kho do vi phạm constraint %s',
                    COALESCE(v_constraint_name, '<không xác định>')
                ),
                DETAIL = COALESCE(v_error_detail, 'Không có thông tin chi tiết');
    END;

    RAISE NOTICE
        'Đã hủy order %, hoàn % sản phẩm %, stock mới %',
        p_order_id,
        v_quantity,
        v_product_name,
        v_new_stock;
END;
$procedure$;
```

---

## 4. Test rollback khi hoàn stock thất bại

Chuẩn hóa Chuột về stock `20`, tạo order quantity `2`, sau đó thêm constraint buộc thao tác hoàn kho thất bại:

```sql
DELETE FROM procedure_lab_1.orders WHERE product_id = 2;

UPDATE procedure_lab_1.products
SET stock = 20,
    updated_at = clock_timestamp()
WHERE id = 2;

CALL procedure_lab_1.sp_create_order(2, 2);

SELECT id AS cancel_order_id
FROM procedure_lab_1.orders
WHERE product_id = 2
ORDER BY id DESC
LIMIT 1
\gset

-- Stock hiện tại là 18 nên constraint được tạo thành công.
ALTER TABLE procedure_lab_1.products
ADD CONSTRAINT reject_stock_above_19_for_exception_demo
CHECK (stock <= 19);

CALL procedure_lab_1.sp_cancel_order(:cancel_order_id);
```

Procedure đã chạy `UPDATE orders SET status = 'cancelled'` trước khi update product. Tuy nhiên update product vi phạm constraint, nên toàn bộ inner block tự rollback.

Kiểm tra:

```sql
SELECT id, status
FROM procedure_lab_1.orders
WHERE id = :cancel_order_id;

SELECT stock
FROM procedure_lab_1.products
WHERE id = 2;
```

Kết quả bắt buộc:

```text
order.status = created
product.stock = 18
```

Dọn constraint test:

```sql
ALTER TABLE procedure_lab_1.products
DROP CONSTRAINT reject_stock_above_19_for_exception_demo;
```

Sau khi dọn constraint, gọi lại:

```sql
CALL procedure_lab_1.sp_cancel_order(:cancel_order_id);
```

Lần này order chuyển thành `cancelled` và stock trở lại `20`.

---

## 5. Sơ đồ transaction và exception

```text
Transaction của caller
│
├─ validate input
│
├─ inner BEGIN                         ← tạo subtransaction
│  ├─ SELECT ... FOR UPDATE
│  ├─ UPDATE dữ liệu
│  ├─ INSERT/UPDATE dữ liệu liên quan
│  └─ lỗi?
│     ├─ không → thoát inner block
│     └─ có   → tự rollback inner block
│                → chạy handler
│                → RAISE EXCEPTION ra caller
│
└─ caller quyết định COMMIT/ROLLBACK
```

Không viết:

```sql
BEGIN
    -- xử lý
    COMMIT;              -- sai
EXCEPTION WHEN OTHERS THEN
    ROLLBACK;            -- sai
END;
```

Block có `EXCEPTION` là subtransaction. PostgreSQL tự rollback phần bên trong khi lỗi xảy ra; transaction control phải do caller quản lý trong hai procedure của bài này.

---

## 6. So sánh hai cách viết đáp án

| Tiêu chí | Đáp án gốc | Đáp án dùng `EXCEPTION` |
|---|---|---|
| Phát hiện không có row | `IF NOT FOUND` | `INTO STRICT` + `WHEN no_data_found` |
| Lỗi constraint | Truyền nguyên lỗi ra caller | Lấy diagnostics, bổ sung context rồi ném lại |
| Rollback | Transaction ngoài rollback | Inner block tự rollback, sau đó lỗi tiếp tục ra transaction ngoài |
| `WHEN OTHERS` | Không dùng | Không dùng |
| `COMMIT`/`ROLLBACK` trong procedure | Không dùng | Không dùng |
| Race condition | `FOR UPDATE` | Vẫn dùng `FOR UPDATE` |

Với nghiệp vụ đơn giản, đáp án gốc ngắn và dễ đọc hơn. Dùng exception handler khi thực sự cần phân loại lỗi, thêm context hoặc cho phép một phần khác của procedure tiếp tục có chủ đích.

---

## 7. Kiểm thử còn lại

Sau khi dọn các constraint test, chạy lại toàn bộ test input, caller rollback và hai session đồng thời trong [đề bài](1-postgresql-procedure-bai-tap.md).

Đáp án không dùng exception handler: [Đáp án thực hành 1 cơ bản](1-postgresql-procedure-bai-tap-dap-an.md).

Lý thuyết liên quan: [EXCEPTION là subtransaction](../postgresql-procedure-chi-tiet.md#5-exception-là-subtransaction-không-phải-chỗ-đặt-rollback).
