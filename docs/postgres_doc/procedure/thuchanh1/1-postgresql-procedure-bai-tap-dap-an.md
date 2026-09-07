# Đáp án thực hành 1 — PostgreSQL PROCEDURE cơ bản

Chạy phần tạo bảng và dữ liệu trong [đề bài](1-postgresql-procedure-bai-tap.md) trước khi tạo procedure.

---

## 1. Lời giải Bài 1 — `sp_create_order`

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

    -- Race-condition guard: khóa row product đến cuối transaction.
    -- Một order đồng thời cho cùng product phải chờ tại đây.
    SELECT p.name,
           p.price,
           p.stock
    INTO v_product_name,
         v_unit_price,
         v_current_stock
    FROM procedure_lab_1.products AS p
    WHERE p.id = p_product_id
      AND p.is_active
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Product %s không tồn tại hoặc đã ngừng bán',
                p_product_id
            );
    END IF;

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

    RAISE NOTICE
        'Đã tạo order % cho %, số lượng %, còn lại %',
        v_order_id,
        v_product_name,
        p_quantity,
        v_remaining_stock;
END;
$procedure$;
```

---

## 2. Vì sao procedure atomic?

Procedure không có `COMMIT`, `ROLLBACK` hoặc exception handler:

```text
SELECT product → UPDATE stock → INSERT order
```

Ba bước chạy trong transaction của caller. Nếu `INSERT orders` lỗi sau khi stock đã được update:

- Ở chế độ autocommit, PostgreSQL tự rollback statement transaction bị lỗi và khôi phục stock.
- Trong `BEGIN ... COMMIT` thủ công, transaction chuyển sang trạng thái aborted; caller phải chạy `ROLLBACK`, khi đó stock được khôi phục.

Procedure không cần tự bắt lỗi rồi rollback.

`SELECT ... FOR UPDATE` khóa product được chọn cho đến khi transaction kết thúc. Hai transaction không thể cùng đọc một giá trị stock cũ rồi cùng trừ trên giá trị đó.

---

## 3. Xử lý race condition khi hai người cùng đặt hàng

Giả sử Laptop còn `5`, user A và user B cùng đặt `4`:

Nếu bỏ `FOR UPDATE`, cả hai transaction có thể cùng đọc `stock = 5`, cùng vượt qua validation, cùng ghi `stock = 1` và tạo hai order có tổng quantity `8`. Constraint `stock >= 0` không phát hiện được lỗi này vì stock cuối vẫn là `1`; đây là lost update dẫn đến overselling.

Với `FOR UPDATE`, luồng thực tế được tuần tự hóa trên row của Laptop:

```text
User A                                      User B
BEGIN                                       CALL sp_create_order(1, 4)
CALL sp_create_order(1, 4)                  └─ SELECT ... FOR UPDATE: chờ
└─ khóa product 1
└─ đọc stock = 5
└─ update stock = 1
└─ insert order
COMMIT                                      └─ nhận khóa sau A
                                            └─ đọc row mới: stock = 1
                                            └─ 1 < 4 → RAISE EXCEPTION
```

Kết quả: một order thành công, một order thất bại, stock còn `1`. Không có overselling và stock không âm.

Ở isolation mặc định `READ COMMITTED`, câu `SELECT ... FOR UPDATE` đang chờ sẽ khóa và trả về phiên bản row mới sau khi transaction trước commit. Nếu transaction trước rollback, user B nhận row cũ và có thể đặt hàng thành công.

Ở `REPEATABLE READ` hoặc `SERIALIZABLE`, thay vì nhận phiên bản mới, transaction chờ có thể lỗi `SQLSTATE 40001` khi row đã bị thay đổi. Ứng dụng phải retry **toàn bộ transaction**, không chỉ chạy lại riêng câu `UPDATE`.

Điểm quyết định là transaction boundary: lock chỉ được giữ đến `COMMIT`/`ROLLBACK`. Vì procedure không tự commit, một top-level `CALL` ở chế độ autocommit giữ lock xuyên suốt cả chuỗi kiểm tra stock → update stock → insert order.

### 3.1. Test bằng hai cửa sổ `psql`

Chuẩn hóa dữ liệu trước khi test:

```sql
DELETE FROM procedure_lab_1.orders WHERE product_id = 1;

UPDATE procedure_lab_1.products
SET stock = 5,
    updated_at = clock_timestamp()
WHERE id = 1;
```

Mở **cửa sổ A** và chạy:

```sql
BEGIN;

CALL procedure_lab_1.sp_create_order(1, 4);

-- Chỉ để test: giữ transaction và row lock trong 10 giây.
SELECT pg_sleep(10);

COMMIT;
```

Trong lúc cửa sổ A đang `pg_sleep`, mở **cửa sổ B** và chạy:

```sql
\timing on

CALL procedure_lab_1.sp_create_order(1, 4);
```

Cửa sổ B phải chờ A commit, sau đó báo lỗi tương tự:

```text
ERROR: Không đủ tồn kho cho Laptop: còn 1, yêu cầu 4
```

Kiểm tra sau khi cả hai cửa sổ kết thúc:

```sql
SELECT id, name, stock
FROM procedure_lab_1.products
WHERE id = 1;

SELECT count(*) AS successful_orders,
       COALESCE(SUM(quantity), 0) AS sold_quantity
FROM procedure_lab_1.orders
WHERE product_id = 1;
```

Kết quả bắt buộc:

```text
stock = 1
successful_orders = 1
sold_quantity = 4
```

> `pg_sleep` chỉ dùng để quan sát lock trong bài lab, không đặt vào procedure production.

---

## 4. Vì sao kiểm tra `FOUND` ngay sau `SELECT`?

`FOUND` phản ánh kết quả của câu lệnh PL/pgSQL gần nhất có cập nhật nó. Vì vậy phải kiểm tra ngay sau:

```sql
SELECT ... INTO ...;

IF NOT FOUND THEN
    RAISE EXCEPTION ...;
END IF;
```

Nếu chạy một `UPDATE` hoặc câu lệnh khác trước `IF NOT FOUND`, giá trị `FOUND` có thể đã đổi và việc kiểm tra product sẽ sai.

---

## 5. Kiểm thử nhanh

```sql
CALL procedure_lab_1.sp_create_order(2, 3);

SELECT id, name, stock
FROM procedure_lab_1.products
ORDER BY id;

SELECT id, product_id, quantity, unit_price, total_amount, status
FROM procedure_lab_1.orders
ORDER BY id;
```

Kết quả chính:

```text
Chuột.stock = 17
orders.quantity = 3
orders.unit_price = 500000.00
orders.total_amount = 1500000.00
orders.status = created
```

Tiếp tục chạy toàn bộ test lỗi, caller rollback và hai session đồng thời trong [đề bài](1-postgresql-procedure-bai-tap.md).

---

## 6. Lời giải Bài 2 — `sp_cancel_order`

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_1.sp_cancel_order(
    IN p_order_id bigint
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_product_id   bigint;
    v_quantity     integer;
    v_order_status text;
    v_product_name text;
    v_new_stock    integer;
BEGIN
    IF p_order_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_order_id không được NULL';
    END IF;

    -- Khóa order trước khi đọc status. Transaction hủy cùng order
    -- phải chờ ở đây và sẽ thấy status mới sau khi nhận được lock.
    SELECT o.product_id,
           o.quantity,
           o.status
    INTO v_product_id,
         v_quantity,
         v_order_status
    FROM procedure_lab_1.orders AS o
    WHERE o.id = p_order_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Order %s không tồn tại', p_order_id);
    END IF;

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

## 7. Vì sao không bị hoàn kho hai lần?

Điểm quan trọng là khóa row order **trước** khi kiểm tra `status`:

```text
User A                                      User B
BEGIN                                       CALL sp_cancel_order(10)
CALL sp_cancel_order(10)                    └─ SELECT ... FOR UPDATE: chờ
└─ khóa order 10
└─ đọc status = created
└─ đổi status = cancelled
└─ cộng stock
COMMIT                                      └─ nhận khóa sau A
                                            └─ đọc status = cancelled
                                            └─ RAISE EXCEPTION
                                            └─ không cộng stock
```

Nếu chỉ `SELECT` thông thường, A và B có thể cùng đọc `status = 'created'` trước khi transaction kia update. Khi đó cả hai đều vượt qua validation và có thể cộng stock hai lần.

Ở isolation mặc định `READ COMMITTED`, transaction B đang chờ `FOR UPDATE` sẽ đọc phiên bản row mới sau khi A commit. Vì vậy B thấy `cancelled` và bị chặn bởi quy tắc nghiệp vụ. Nếu A rollback, B thấy lại `created` và có thể hủy hợp lệ.

Khóa được giữ đến cuối transaction. Procedure không tự `COMMIT` hoặc `ROLLBACK`, nên việc đổi trạng thái order và cộng stock thuộc cùng một transaction atomic. Nếu `UPDATE products` hoặc bất kỳ lệnh sau đó lỗi, thay đổi `orders.status` cũng bị rollback.

> Việc kiểm tra `status` chỉ chống hoàn kho lặp lại. Với API thực tế, vẫn nên có idempotency key để nhận diện các request retry là cùng một thao tác.

---

## 8. Kiểm thử Bài 2

### 8.1. Hủy thành công và từ chối lần hủy thứ hai

```sql
DELETE FROM procedure_lab_1.orders;

UPDATE procedure_lab_1.products
SET stock = 20,
    updated_at = clock_timestamp()
WHERE id = 2;

CALL procedure_lab_1.sp_create_order(2, 2);

SELECT id AS cancel_order_id
FROM procedure_lab_1.orders
ORDER BY id DESC
LIMIT 1
\gset

-- Stock: 20 → 18 → 20.
CALL procedure_lab_1.sp_cancel_order(:cancel_order_id);

SELECT o.id, o.status, p.name, p.stock
FROM procedure_lab_1.orders AS o
JOIN procedure_lab_1.products AS p ON p.id = o.product_id
WHERE o.id = :cancel_order_id;

-- Phải báo lỗi; stock vẫn là 20.
CALL procedure_lab_1.sp_cancel_order(:cancel_order_id);
```

Kết quả sau lần gọi đầu tiên:

```text
status = cancelled
name = Chuột
stock = 20
```

### 8.2. Hai session cùng hủy

Chạy test hai cửa sổ ở mục 8.7 của [đề bài](1-postgresql-procedure-bai-tap.md). Cửa sổ B phải chờ A kết thúc rồi báo lỗi trạng thái. Kết quả cuối cùng là order `cancelled` và Laptop có stock `5`, chứng minh quantity chỉ được hoàn một lần.

---

## 9. Tài liệu PostgreSQL liên quan

- [Row-level locks và `SELECT ... FOR UPDATE`](https://www.postgresql.org/docs/15/explicit-locking.html#LOCKING-ROWS)
- [Transaction isolation](https://www.postgresql.org/docs/15/transaction-iso.html)
