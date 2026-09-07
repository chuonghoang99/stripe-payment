# Thực hành 2 — Chuyển tồn kho an toàn giữa hai kho

Viết một procedure chuyển tồn kho của một product từ kho nguồn sang kho đích.

Bài này ở mức **trung bình**:

- Khó hơn thực hành 1 vì phải khóa hai row theo thứ tự cố định và xử lý idempotency.
- Dễ hơn thực hành 3 vì chưa dùng dynamic SQL, temp table, batch loop hoặc commit giữa chừng.
- Toàn bộ lần chuyển kho vẫn là một transaction atomic do caller quản lý.

Phần setup đã tạo sẵn bảng và dữ liệu. Phần cần tự viết chỉ là `sp_transfer_stock`.

---

## 1. Kiến thức cần áp dụng

- Validate input và trạng thái dữ liệu liên quan.
- Gán biến bằng `:=` và `SELECT ... INTO`.
- Dùng `FOUND` ngay sau `INSERT` hoặc `SELECT` cần kiểm tra.
- Dùng `INSERT ... ON CONFLICT DO NOTHING` để claim một `request_id`.
- Khóa hai row bằng `SELECT ... FOR UPDATE`.
- Luôn lấy nhiều lock theo cùng một thứ tự để giảm nguy cơ deadlock.
- Update hai row và insert lịch sử trong một transaction atomic.
- Chống xử lý lại cùng request nhưng phát hiện khi một request ID bị dùng với payload khác.

---

## 2. Khởi động PostgreSQL

Project đã có [compose.yaml](../../../../compose.yaml). Chạy từ thư mục gốc project:

```bash
docker compose up -d postgres
docker compose ps postgres
docker compose exec postgres psql -U postgres -d postgres
```

Chờ container `postgres-lab` có trạng thái `healthy` rồi mới kết nối.

---

## 3. Tạo bảng và dữ liệu mẫu

Chạy toàn bộ block sau trong `psql`. Script chỉ xóa schema `procedure_lab_2`.

```sql
DROP SCHEMA IF EXISTS procedure_lab_2 CASCADE;
CREATE SCHEMA procedure_lab_2;

CREATE TABLE procedure_lab_2.products (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text NOT NULL UNIQUE,
    is_active  boolean NOT NULL DEFAULT true
);

CREATE TABLE procedure_lab_2.warehouses (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text NOT NULL UNIQUE,
    is_active  boolean NOT NULL DEFAULT true
);

CREATE TABLE procedure_lab_2.inventory (
    product_id    bigint NOT NULL
                  REFERENCES procedure_lab_2.products(id),
    warehouse_id  bigint NOT NULL
                  REFERENCES procedure_lab_2.warehouses(id),
    stock         integer NOT NULL CHECK (stock >= 0),
    updated_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (product_id, warehouse_id)
);

CREATE TABLE procedure_lab_2.stock_transfers (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id         uuid NOT NULL UNIQUE,
    product_id         bigint NOT NULL
                       REFERENCES procedure_lab_2.products(id),
    from_warehouse_id  bigint NOT NULL
                       REFERENCES procedure_lab_2.warehouses(id),
    to_warehouse_id    bigint NOT NULL
                       REFERENCES procedure_lab_2.warehouses(id),
    quantity           integer NOT NULL CHECK (quantity > 0),
    created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (from_warehouse_id <> to_warehouse_id)
);

INSERT INTO procedure_lab_2.products(name, is_active)
VALUES
    ('Laptop', true),
    ('Chuột', true),
    ('Bàn phím cũ', false);

INSERT INTO procedure_lab_2.warehouses(name, is_active)
VALUES
    ('Kho HCM', true),
    ('Kho Hà Nội', true),
    ('Kho đã đóng', false),
    ('Kho Đà Nẵng', true);

INSERT INTO procedure_lab_2.inventory(product_id, warehouse_id, stock)
VALUES
    (1, 1, 10),
    (1, 2, 3),
    (1, 3, 4),
    (2, 1, 20),
    (2, 2, 5),
    (3, 1, 8),
    (3, 2, 2);
```

Kho Đà Nẵng active nhưng chưa có row tồn kho. Dữ liệu này dùng để test trường hợp inventory chưa được cấu hình.

Kiểm tra dữ liệu:

```sql
SELECT p.name AS product_name,
       w.name AS warehouse_name,
       i.stock
FROM procedure_lab_2.inventory AS i
JOIN procedure_lab_2.products AS p ON p.id = i.product_id
JOIN procedure_lab_2.warehouses AS w ON w.id = i.warehouse_id
ORDER BY p.id, w.id;
```

---

## 4. Đề bài

Viết procedure có chữ ký chính xác:

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_2.sp_transfer_stock(
    IN p_request_id uuid,
    IN p_product_id bigint,
    IN p_from_warehouse_id bigint,
    IN p_to_warehouse_id bigint,
    IN p_quantity integer
)
LANGUAGE plpgsql
AS $$
    -- Tự viết
$$;
```

Ví dụ gọi:

```sql
CALL procedure_lab_2.sp_transfer_stock(
    '11111111-1111-1111-1111-111111111111',
    1,
    1,
    2,
    4
);
```

### 4.1. Quy tắc nghiệp vụ

1. Tất cả input không được `NULL`.
2. `p_quantity` phải nằm trong khoảng `1..1000`.
3. Kho nguồn và kho đích phải khác nhau.
4. Product phải tồn tại và đang active.
5. Cả hai warehouse phải tồn tại và đang active.
6. Phải có row `inventory` cho cả cặp product–kho nguồn và product–kho đích.
7. Stock kho nguồn phải lớn hơn hoặc bằng `p_quantity`.
8. Khi thành công:
   - Trừ quantity tại kho nguồn.
   - Cộng quantity tại kho đích.
   - Cập nhật `updated_at` của cả hai row inventory.
   - Có đúng một row trong `stock_transfers` cho `request_id`.
9. Tổng stock của product ở hai kho không thay đổi sau transfer.

### 4.2. Idempotency bằng `request_id`

Trước khi thay đổi inventory, procedure phải claim request bằng:

```sql
INSERT INTO procedure_lab_2.stock_transfers(...)
VALUES (...)
ON CONFLICT (request_id) DO NOTHING;
```

Sau câu `INSERT`, kiểm tra `FOUND` ngay lập tức:

- Insert thành công: tiếp tục xử lý transfer.
- `FOUND = false`: đọc transfer đã tồn tại theo `request_id`.
  - Nếu toàn bộ `product_id`, kho nguồn, kho đích và quantity giống input: in notice và kết thúc procedure, không update stock.
  - Nếu payload khác: báo lỗi vì `request_id` đang bị tái sử dụng sai.

Notice cho request trùng hợp lệ:

```text
Request <request_id> đã được xử lý trước đó; bỏ qua
```

Nếu transfer thất bại sau khi claim, row `stock_transfers` vừa insert cũng phải rollback. Caller có thể retry lại cùng `request_id` sau khi sửa nguyên nhân lỗi.

### 4.3. Chống race condition và deadlock

Một transfer cần khóa hai row inventory. Không được khóa theo thứ tự “nguồn trước, đích sau”, vì hai request ngược chiều có thể lấy lock theo thứ tự đối nghịch:

```text
Request A: Kho 1 → Kho 2, giữ lock Kho 1 rồi chờ Kho 2
Request B: Kho 2 → Kho 1, giữ lock Kho 2 rồi chờ Kho 1
```

Hãy tính:

```sql
v_first_warehouse_id  := LEAST(p_from_warehouse_id, p_to_warehouse_id);
v_second_warehouse_id := GREATEST(p_from_warehouse_id, p_to_warehouse_id);
```

Sau đó khóa inventory theo đúng thứ tự:

1. Product tại `v_first_warehouse_id` bằng `SELECT ... INTO ... FOR UPDATE`.
2. Product tại `v_second_warehouse_id` bằng `SELECT ... INTO ... FOR UPDATE`.

Mọi lời gọi procedure đều khóa warehouse ID nhỏ trước, ID lớn sau. Sau khi có cả hai lock, mới xác định giá trị nào là stock nguồn và kiểm tra đủ hàng.

Hai request cùng lấy hàng từ một kho không được cùng kiểm tra trên một stock cũ. Request đến sau phải chờ, đọc stock mới và báo lỗi nếu không còn đủ hàng.

Các expected result của bài lab giả định isolation mặc định `READ COMMITTED`. Nếu chạy ở `REPEATABLE READ` hoặc `SERIALIZABLE`, caller phải sẵn sàng retry toàn bộ transaction khi PostgreSQL trả serialization failure.

### 4.4. Transaction boundary

- Không viết `COMMIT` hoặc `ROLLBACK` trong procedure.
- Không bắt `WHEN OTHERS`.
- Để exception truyền về caller.
- Claim request, trừ nguồn, cộng đích và lịch sử transfer phải thuộc cùng một transaction.

Nếu update kho đích lỗi sau khi kho nguồn đã bị trừ, toàn bộ lần gọi phải rollback: stock nguồn, stock đích và transfer log đều trở về trạng thái trước `CALL`.

### 4.5. Notice khi thành công

```text
Đã chuyển <quantity> <product_name> từ <source_name> sang <destination_name>; tồn nguồn <source_stock>, tồn đích <destination_stock>
```

### 4.6. Thứ tự xử lý gợi ý

```text
Validate input cơ bản và dữ liệu active
    ↓
INSERT stock_transfers ... ON CONFLICT DO NOTHING
    ↓
Request trùng? → so payload → bỏ qua hoặc báo lỗi
    ↓
Tính warehouse ID nhỏ/lớn
    ↓
Khóa inventory ID nhỏ FOR UPDATE
    ↓
Khóa inventory ID lớn FOR UPDATE
    ↓
Xác định stock nguồn, kiểm tra đủ hàng
    ↓
UPDATE kho nguồn và kho đích
    ↓
RAISE NOTICE
```

### 4.7. Skeleton

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
    -- TODO 1: validate input và dữ liệu active

    -- TODO 2: claim request bằng INSERT ... ON CONFLICT DO NOTHING

    -- TODO 3: nếu NOT FOUND, so payload cũ rồi RETURN hoặc RAISE EXCEPTION

    -- TODO 4: dùng LEAST/GREATEST và khóa hai inventory row theo thứ tự

    -- TODO 5: xác định stock nguồn và kiểm tra đủ stock

    -- TODO 6: update source, destination và updated_at

    -- TODO 7: RAISE NOTICE
END;
$procedure$;
```

---

## 5. Test chức năng và idempotency

### 5.1. Transfer thành công

```sql
CALL procedure_lab_2.sp_transfer_stock(
    '11111111-1111-1111-1111-111111111111',
    1,
    1,
    2,
    4
);
```

Kiểm tra:

```sql
SELECT warehouse_id, stock
FROM procedure_lab_2.inventory
WHERE product_id = 1
  AND warehouse_id IN (1, 2)
ORDER BY warehouse_id;

SELECT request_id,
       product_id,
       from_warehouse_id,
       to_warehouse_id,
       quantity
FROM procedure_lab_2.stock_transfers
ORDER BY id;
```

Kết quả bắt buộc:

| Kiểm tra | Trước | Sau |
|---|---:|---:|
| Laptop tại Kho HCM | 10 | 6 |
| Laptop tại Kho Hà Nội | 3 | 7 |
| Tổng hai kho | 13 | 13 |
| Số transfer log | 0 | 1 |

### 5.2. Retry đúng request

Gọi lại nguyên lệnh ở mục 5.1. Procedure phải in notice “đã được xử lý trước đó” và không chuyển stock lần hai.

Kết quả vẫn là:

```text
Kho HCM = 6
Kho Hà Nội = 7
stock_transfers = 1 row
```

### 5.3. Tái sử dụng request ID với payload khác

```sql
CALL procedure_lab_2.sp_transfer_stock(
    '11111111-1111-1111-1111-111111111111',
    1,
    1,
    2,
    5
);
```

Procedure phải báo lỗi vì quantity khác request cũ. Stock vẫn là `6` và `7`; transfer log vẫn chỉ có một row.

---

## 6. Test dữ liệu không hợp lệ

Chạy từng lệnh riêng ở chế độ autocommit. Mỗi lệnh phải báo lỗi:

```sql
CALL procedure_lab_2.sp_transfer_stock(NULL, 1, 1, 2, 1);

CALL procedure_lab_2.sp_transfer_stock(
    '20000000-0000-0000-0000-000000000001',
    NULL, 1, 2, 1
);

CALL procedure_lab_2.sp_transfer_stock(
    '20000000-0000-0000-0000-000000000002',
    1, 1, 2, 0
);

-- Kho nguồn và đích giống nhau
CALL procedure_lab_2.sp_transfer_stock(
    '20000000-0000-0000-0000-000000000003',
    1, 1, 1, 1
);

-- Product inactive
CALL procedure_lab_2.sp_transfer_stock(
    '20000000-0000-0000-0000-000000000004',
    3, 1, 2, 1
);

-- Warehouse inactive
CALL procedure_lab_2.sp_transfer_stock(
    '20000000-0000-0000-0000-000000000005',
    1, 1, 3, 1
);

-- Kho Đà Nẵng active nhưng chưa có inventory cho Laptop
CALL procedure_lab_2.sp_transfer_stock(
    '20000000-0000-0000-0000-000000000006',
    1, 1, 4, 1
);

-- Không đủ stock ở Kho Hà Nội
CALL procedure_lab_2.sp_transfer_stock(
    '20000000-0000-0000-0000-000000000007',
    1, 2, 1, 100
);
```

Sau tất cả lỗi, stock Laptop vẫn là `6` và `7`. Chỉ request `1111...` tồn tại trong `stock_transfers`; các claim của transaction lỗi phải được rollback.

---

## 7. Test caller rollback

```sql
BEGIN;

CALL procedure_lab_2.sp_transfer_stock(
    '22222222-2222-2222-2222-222222222222',
    2,
    1,
    2,
    5
);

-- Bên trong transaction: Chuột ở Kho HCM = 15, Kho Hà Nội = 10.
SELECT warehouse_id, stock
FROM procedure_lab_2.inventory
WHERE product_id = 2
ORDER BY warehouse_id;

ROLLBACK;

-- Sau rollback: trở lại 20 và 5; request 2222... không tồn tại.
SELECT warehouse_id, stock
FROM procedure_lab_2.inventory
WHERE product_id = 2
ORDER BY warehouse_id;

SELECT count(*)
FROM procedure_lab_2.stock_transfers
WHERE request_id = '22222222-2222-2222-2222-222222222222';
```

---

## 8. Test race condition: hai request cùng lấy một nguồn

Chuẩn hóa dữ liệu:

```sql
DELETE FROM procedure_lab_2.stock_transfers WHERE product_id = 1;

UPDATE procedure_lab_2.inventory
SET stock = CASE warehouse_id WHEN 1 THEN 5 WHEN 2 THEN 0 END,
    updated_at = clock_timestamp()
WHERE product_id = 1
  AND warehouse_id IN (1, 2);
```

Mở **cửa sổ A**:

```sql
BEGIN;

CALL procedure_lab_2.sp_transfer_stock(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    1, 1, 2, 4
);

SELECT pg_sleep(10);
COMMIT;
```

Trong lúc A đang sleep, mở **cửa sổ B**:

```sql
\timing on

CALL procedure_lab_2.sp_transfer_stock(
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    1, 1, 2, 4
);
```

B phải chờ A commit, sau đó đọc stock nguồn mới là `1` và báo không đủ hàng.

```sql
SELECT warehouse_id, stock
FROM procedure_lab_2.inventory
WHERE product_id = 1
  AND warehouse_id IN (1, 2)
ORDER BY warehouse_id;

SELECT request_id, quantity
FROM procedure_lab_2.stock_transfers
WHERE product_id = 1
ORDER BY id;
```

Kết quả bắt buộc:

- Kho HCM còn `1`.
- Kho Hà Nội có `4`.
- Chỉ request `aaaa...` có transfer log.
- Request `bbbb...` thất bại nên claim của nó cũng rollback.
- Không có overselling và stock không âm.

---

## 9. Bài test thêm: hai transfer ngược chiều

Phần này dùng để kiểm tra thứ tự lock nhất quán.

Chuẩn hóa hai kho về stock `10`:

```sql
DELETE FROM procedure_lab_2.stock_transfers WHERE product_id = 1;

UPDATE procedure_lab_2.inventory
SET stock = 10,
    updated_at = clock_timestamp()
WHERE product_id = 1
  AND warehouse_id IN (1, 2);
```

Cửa sổ A:

```sql
BEGIN;
CALL procedure_lab_2.sp_transfer_stock(
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    1, 1, 2, 2
);
SELECT pg_sleep(10);
COMMIT;
```

Cửa sổ B chạy trong lúc A sleep:

```sql
\timing on
CALL procedure_lab_2.sp_transfer_stock(
    'dddddddd-dddd-dddd-dddd-dddddddddddd',
    1, 2, 1, 2
);
```

B có thể phải chờ A nhưng không được tạo deadlock. Sau khi cả hai thành công, stock hai kho trở lại `10` và có hai transfer log.

> `pg_sleep` chỉ dùng để quan sát lock trong bài lab, không đặt vào procedure production.

---

## 10. Checklist tự chấm

- [ ] Validate toàn bộ input, quantity và hai warehouse khác nhau.
- [ ] Chỉ xử lý product và warehouse active.
- [ ] Claim `request_id` bằng unique constraint + `ON CONFLICT DO NOTHING`.
- [ ] Kiểm tra `FOUND` ngay sau câu `INSERT` claim.
- [ ] Retry cùng payload không chuyển stock lần hai.
- [ ] Cùng request ID nhưng payload khác phải báo lỗi.
- [ ] Tính thứ tự lock bằng `LEAST` và `GREATEST`.
- [ ] Khóa đủ hai row inventory bằng `FOR UPDATE` theo thứ tự ID tăng dần.
- [ ] Kiểm tra inventory thiếu và stock nguồn không đủ.
- [ ] Update đúng nguồn, đích và `updated_at`.
- [ ] Không có `COMMIT`, `ROLLBACK` hoặc `WHEN OTHERS`.
- [ ] Caller rollback được cả inventory và transfer log.
- [ ] Test hai session không oversell.
- [ ] Test ngược chiều không deadlock.

Đối chiếu sau khi tự làm: [Đáp án thực hành 2](2-postgresql-procedure-bai-tap-dap-an.md).

Lý thuyết liên quan: [PROCEDURE trong PostgreSQL — Chi tiết đầy đủ](../postgresql-procedure-chi-tiet.md).
