# Thực hành 1 — PostgreSQL PROCEDURE cơ bản

Viết hai procedure xử lý vòng đời đơn hàng trên hai bảng `products` và `orders` đã có sẵn:

1. `sp_create_order`: tạo đơn và trừ tồn kho.
2. `sp_cancel_order`: hủy đơn và hoàn tồn kho đúng một lần.

Phần cần tự viết chỉ là hai procedure; không cần tạo thêm bảng.

Bài này dành cho người mới, tập trung vào:

- Khai báo input parameter và biến cục bộ.
- Gán dữ liệu bằng `SELECT ... INTO`.
- Kiểm tra điều kiện bằng `IF` và `FOUND`.
- Chống race condition bằng row lock khi hai user đặt cùng product.
- Cập nhật nhiều bảng trong một transaction atomic.
- Lấy ID vừa insert bằng `INSERT ... RETURNING ... INTO`.
- Báo lỗi bằng `RAISE EXCEPTION` và in kết quả bằng `RAISE NOTICE`.

Không cần dùng dynamic SQL, temp table, cursor hoặc exception handler.

---

## 1. Khởi động PostgreSQL

Project đã có [compose.yaml](../../../../compose.yaml). Chạy từ thư mục gốc project:

```bash
docker compose up -d postgres
docker compose ps postgres
docker compose exec postgres psql -U postgres -d postgres
```

Chờ container `postgres-lab` có trạng thái `healthy` rồi mới kết nối.

---

## 2. Tạo bảng và dữ liệu mẫu

Chạy toàn bộ block sau trong `psql`. Script chỉ xóa schema `procedure_lab_1`.

```sql
DROP SCHEMA IF EXISTS procedure_lab_1 CASCADE;
CREATE SCHEMA procedure_lab_1;

CREATE TABLE procedure_lab_1.products (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL UNIQUE,
    price       numeric(14, 2) NOT NULL CHECK (price > 0),
    stock       integer NOT NULL CHECK (stock >= 0),
    is_active   boolean NOT NULL DEFAULT true,
    updated_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE procedure_lab_1.orders (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id    bigint NOT NULL REFERENCES procedure_lab_1.products(id),
    quantity      integer NOT NULL CHECK (quantity > 0),
    unit_price    numeric(14, 2) NOT NULL CHECK (unit_price > 0),
    total_amount  numeric(14, 2) NOT NULL CHECK (total_amount > 0),
    status        text NOT NULL DEFAULT 'created'
                  CHECK (status IN ('created', 'cancelled')),
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO procedure_lab_1.products(name, price, stock, is_active)
VALUES
    ('Laptop',   20000000,  5, true),
    ('Chuột',      500000, 20, true),
    ('Bàn phím',  1000000,  8, false);
```

Kiểm tra dữ liệu:

```sql
SELECT id, name, price, stock, is_active
FROM procedure_lab_1.products
ORDER BY id;
```

Kết quả ban đầu:

| id | Product | Price | Stock | Active |
|---:|---|---:|---:|---|
| 1 | Laptop | 20,000,000.00 | 5 | `true` |
| 2 | Chuột | 500,000.00 | 20 | `true` |
| 3 | Bàn phím | 1,000,000.00 | 8 | `false` |

---

## 3. Bài 1 — Tạo đơn hàng và trừ tồn kho

Viết procedure có chữ ký chính xác:

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_1.sp_create_order(
    IN p_product_id bigint,
    IN p_quantity integer
)
LANGUAGE plpgsql
AS $$
    -- Tự viết
$$;
```

Gọi procedure bằng:

```sql
CALL procedure_lab_1.sp_create_order(2, 3);
```

### 3.1. Quy tắc nghiệp vụ

1. `p_product_id` không được `NULL`.
2. `p_quantity` phải nằm trong khoảng `1..100`.
3. Product phải tồn tại và có `is_active = true`.
4. `stock` phải lớn hơn hoặc bằng số lượng đặt.
5. Mỗi lần gọi thành công:
   - Trừ `p_quantity` khỏi `products.stock`.
   - Cập nhật `products.updated_at`.
   - Insert đúng một row vào `orders`.
   - `unit_price` lấy từ giá hiện tại của product.
   - `total_amount = unit_price * p_quantity`.
   - `status = 'created'`.
6. Sau khi insert, lấy `orders.id` vào biến `v_order_id` bằng `RETURNING ... INTO`.
7. In notice theo mẫu:

   ```text
   Đã tạo order <order_id> cho <product_name>, số lượng <quantity>, còn lại <remaining_stock>
   ```

### 3.2. Yêu cầu kỹ thuật

- Dùng biến cục bộ cho tên product, đơn giá, tồn kho, order ID và tồn kho còn lại.
- Dùng `SELECT ... INTO ... FOR UPDATE` để đọc và khóa product trước khi kiểm tra stock.
- Hai transaction đặt cùng product không được cùng sử dụng một giá trị stock cũ; transaction đến sau phải chờ row lock và kiểm tra lại stock mới.
- Sau `SELECT`, dùng `IF NOT FOUND` để phát hiện product không tồn tại hoặc inactive.
- Dùng `RAISE EXCEPTION` khi input hoặc nghiệp vụ không hợp lệ.
- Không bắt `WHEN OTHERS`; để lỗi truyền về caller.
- Không viết `COMMIT` hoặc `ROLLBACK` trong procedure.

Toàn bộ `CALL` phải atomic: nếu tạo order thất bại thì stock không được thay đổi; nếu trừ stock thất bại thì không được có order mới.

### 3.3. Thứ tự xử lý gợi ý

```text
Validate input
    ↓
SELECT product INTO biến FOR UPDATE
    ↓
Không tìm thấy? → RAISE EXCEPTION
    ↓
Không đủ stock? → RAISE EXCEPTION
    ↓
UPDATE stock
    ↓
INSERT order RETURNING id INTO biến
    ↓
RAISE NOTICE
```

### 3.4. Skeleton

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
    -- TODO 1: validate p_product_id và p_quantity

    -- TODO 2: SELECT product INTO các biến, chỉ lấy product active, FOR UPDATE

    -- TODO 3: IF NOT FOUND và kiểm tra đủ stock

    -- TODO 4: tính v_remaining_stock rồi UPDATE products

    -- TODO 5: INSERT orders RETURNING id INTO v_order_id

    -- TODO 6: RAISE NOTICE
END;
$procedure$;
```

---

## 4. Test Bài 1 thành công

### 4.1. Tạo order mua ba con chuột

Lưu timestamp hiện tại vào biến `psql`, sau đó gọi procedure:

```sql
SELECT updated_at AS updated_at_before
FROM procedure_lab_1.products
WHERE id = 2
\gset

CALL procedure_lab_1.sp_create_order(2, 3);
```

Kết quả notice tương tự:

```text
NOTICE: Đã tạo order 1 cho Chuột, số lượng 3, còn lại 17
```

Kiểm tra:

```sql
SELECT id,
       name,
       stock,
       updated_at,
       updated_at > :'updated_at_before'::timestamptz AS updated_at_changed
FROM procedure_lab_1.products
WHERE id = 2;

SELECT id, product_id, quantity, unit_price, total_amount, status
FROM procedure_lab_1.orders
ORDER BY id;
```

Kết quả bắt buộc:

| Kiểm tra | Giá trị |
|---|---|
| Stock Chuột | `17` |
| `updated_at_changed` | `true` |
| Số order | `1` |
| `product_id` | `2` |
| `quantity` | `3` |
| `unit_price` | `500,000.00` |
| `total_amount` | `1,500,000.00` |
| `status` | `created` |

---

## 5. Test lỗi Bài 1

Chạy từng lệnh riêng ở chế độ autocommit. Mỗi lệnh phải báo lỗi:

```sql
-- Product ID không được NULL
CALL procedure_lab_1.sp_create_order(NULL, 1);

-- Số lượng không được NULL
CALL procedure_lab_1.sp_create_order(1, NULL);

-- Số lượng không hợp lệ
CALL procedure_lab_1.sp_create_order(1, 0);

-- Vượt giới hạn 100
CALL procedure_lab_1.sp_create_order(1, 101);

-- Không đủ stock: Laptop chỉ còn 5
CALL procedure_lab_1.sp_create_order(1, 99);

-- Product không tồn tại
CALL procedure_lab_1.sp_create_order(999, 1);

-- Product tồn tại nhưng inactive
CALL procedure_lab_1.sp_create_order(3, 1);
```

Sau tất cả các lệnh lỗi:

```sql
SELECT id, name, stock
FROM procedure_lab_1.products
ORDER BY id;

SELECT count(*) AS order_count
FROM procedure_lab_1.orders;
```

Kết quả bắt buộc:

- Laptop vẫn có stock `5`.
- Chuột vẫn có stock `17` từ lần thành công.
- Bàn phím vẫn có stock `8`.
- `order_count` vẫn bằng `1`; không có order rác từ các lần lỗi.

Nếu tự bọc test trong `BEGIN`, sau một exception phải chạy `ROLLBACK` trước khi tiếp tục.

---

## 6. Test caller rollback Bài 1

Procedure không tự commit, nên caller có thể rollback cả lần gọi thành công:

```sql
BEGIN;

CALL procedure_lab_1.sp_create_order(1, 2);

-- Bên trong transaction: Laptop tạm còn 3, tổng số order tạm là 2.
SELECT stock FROM procedure_lab_1.products WHERE id = 1;
SELECT count(*) FROM procedure_lab_1.orders;

ROLLBACK;

-- Sau rollback: Laptop trở lại 5, tổng số order trở lại 1.
SELECT stock FROM procedure_lab_1.products WHERE id = 1;
SELECT count(*) FROM procedure_lab_1.orders;
```

---

## 7. Test race condition Bài 1 bằng hai session

Chuẩn hóa Laptop về stock `5`:

```sql
DELETE FROM procedure_lab_1.orders WHERE product_id = 1;

UPDATE procedure_lab_1.products
SET stock = 5,
    updated_at = clock_timestamp()
WHERE id = 1;
```

Mở cửa sổ `psql` A:

```sql
BEGIN;
CALL procedure_lab_1.sp_create_order(1, 4);
SELECT pg_sleep(10); -- giữ row lock để cửa sổ B có thời gian chạy
COMMIT;
```

Trong lúc A đang sleep, mở cửa sổ `psql` B:

```sql
\timing on
CALL procedure_lab_1.sp_create_order(1, 4);
```

B phải chờ A commit, sau đó báo không đủ hàng. Kiểm tra:

```sql
SELECT stock
FROM procedure_lab_1.products
WHERE id = 1;

SELECT count(*) AS successful_orders,
       COALESCE(SUM(quantity), 0) AS sold_quantity
FROM procedure_lab_1.orders
WHERE product_id = 1;
```

Kết quả bắt buộc:

- Chỉ một lời gọi thành công.
- `stock = 1`.
- `successful_orders = 1`.
- `sold_quantity = 4`.
- Không có overselling và stock không âm.

---

## 8. Bài 2 — Hủy đơn hàng và hoàn tồn kho

Sau khi hoàn thành Bài 1, viết thêm procedure có chữ ký chính xác:

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_1.sp_cancel_order(
    IN p_order_id bigint
)
LANGUAGE plpgsql
AS $$
    -- Tự viết
$$;
```

Gọi procedure bằng:

```sql
CALL procedure_lab_1.sp_cancel_order(1);
```

### 8.1. Quy tắc nghiệp vụ

1. `p_order_id` không được `NULL`.
2. Order phải tồn tại.
3. Chỉ order có `status = 'created'` mới được hủy.
4. Mỗi lần hủy thành công:
   - Đổi `orders.status` thành `cancelled`.
   - Cộng lại đúng `orders.quantity` vào `products.stock`.
   - Cập nhật `products.updated_at`.
5. Một order chỉ được hoàn kho đúng một lần. Nếu gọi lại procedure cho order đã `cancelled`, procedure phải báo lỗi và stock không đổi.
6. In notice theo mẫu:

   ```text
   Đã hủy order <order_id>, hoàn <quantity> sản phẩm <product_name>, stock mới <new_stock>
   ```

### 8.2. Yêu cầu kỹ thuật và race condition

- Dùng `SELECT ... INTO ... FOR UPDATE` để đọc và khóa row order trước khi kiểm tra `status`.
- Lấy `product_id`, `quantity` và `status` của order vào các biến cục bộ.
- Kiểm tra `IF NOT FOUND` ngay sau `SELECT` để phát hiện order không tồn tại.
- Chỉ sau khi xác nhận trạng thái `created` mới được update order và hoàn stock.
- Dùng `UPDATE products ... RETURNING name, stock INTO ...` để lấy tên product và stock mới.
- Không bắt `WHEN OTHERS`; để lỗi truyền về caller.
- Không viết `COMMIT` hoặc `ROLLBACK` trong procedure.

Hai transaction cùng hủy một order phải hoạt động như sau:

```text
Transaction A khóa order và hủy thành công
    ↓
Transaction B chờ row lock
    ↓
A COMMIT
    ↓
B đọc trạng thái mới là cancelled và báo lỗi
    ↓
Stock chỉ được hoàn một lần
```

Nếu kiểm tra trạng thái mà không khóa order, cả A và B có thể cùng đọc `created` rồi cùng cộng stock. Đây là race condition làm sai tồn kho dù constraint `stock >= 0` vẫn hợp lệ.

### 8.3. Thứ tự xử lý gợi ý

```text
Validate p_order_id
    ↓
SELECT order INTO biến FOR UPDATE
    ↓
Không tìm thấy? → RAISE EXCEPTION
    ↓
Status khác created? → RAISE EXCEPTION
    ↓
UPDATE order thành cancelled
    ↓
UPDATE product cộng stock RETURNING name, stock INTO biến
    ↓
RAISE NOTICE
```

### 8.4. Skeleton

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
    -- TODO 1: validate p_order_id

    -- TODO 2: SELECT order INTO các biến và FOR UPDATE

    -- TODO 3: IF NOT FOUND và kiểm tra status = 'created'

    -- TODO 4: UPDATE order thành cancelled

    -- TODO 5: hoàn stock, cập nhật updated_at và RETURNING INTO

    -- TODO 6: RAISE NOTICE
END;
$procedure$;
```

### 8.5. Test Bài 2 thành công

Chuẩn hóa dữ liệu để test không phụ thuộc các bước trước, sau đó tạo một order mới:

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

SELECT stock FROM procedure_lab_1.products WHERE id = 2;
CALL procedure_lab_1.sp_cancel_order(:cancel_order_id);
```

Kiểm tra:

```sql
SELECT id, status
FROM procedure_lab_1.orders
WHERE id = :cancel_order_id;

SELECT id, name, stock
FROM procedure_lab_1.products
WHERE id = 2;
```

Kết quả bắt buộc:

- Trước khi hủy, stock Chuột bằng `18`.
- Sau khi hủy, order có `status = 'cancelled'`.
- Stock Chuột trở lại `20`.

### 8.6. Test lỗi và tính atomic

Mỗi lệnh sau phải báo lỗi:

```sql
-- Order ID không được NULL
CALL procedure_lab_1.sp_cancel_order(NULL);

-- Order không tồn tại
CALL procedure_lab_1.sp_cancel_order(999999);

-- Order vừa test đã cancelled: không được hoàn stock lần thứ hai
CALL procedure_lab_1.sp_cancel_order(:cancel_order_id);
```

Kiểm tra lại stock Chuột vẫn bằng `20`:

```sql
SELECT stock
FROM procedure_lab_1.products
WHERE id = 2;
```

### 8.7. Test hai session cùng hủy một order

Chuẩn hóa Laptop, tạo order mua `4` sản phẩm và lưu ID vào biến `psql`:

```sql
DELETE FROM procedure_lab_1.orders WHERE product_id = 1;

UPDATE procedure_lab_1.products
SET stock = 5,
    updated_at = clock_timestamp()
WHERE id = 1;

CALL procedure_lab_1.sp_create_order(1, 4);

SELECT id AS race_order_id
FROM procedure_lab_1.orders
WHERE product_id = 1
ORDER BY id DESC
LIMIT 1
\gset

\echo :race_order_id
```

Ghi lại giá trị `race_order_id`. Mở **cửa sổ A**, thay `<order_id>` bằng giá trị vừa ghi rồi chạy:

```sql
BEGIN;
CALL procedure_lab_1.sp_cancel_order(<order_id>);
SELECT pg_sleep(10); -- giữ row lock để cửa sổ B có thời gian chạy
COMMIT;
```

Trong lúc A đang sleep, mở **cửa sổ B** và chạy với cùng `<order_id>`:

```sql
\timing on
CALL procedure_lab_1.sp_cancel_order(<order_id>);
```

Cửa sổ B phải chờ A commit, sau đó báo order không còn ở trạng thái `created`. Kiểm tra:

```sql
SELECT id, status
FROM procedure_lab_1.orders
WHERE id = <order_id>;

SELECT stock
FROM procedure_lab_1.products
WHERE id = 1;
```

Kết quả bắt buộc:

- Chỉ cửa sổ A hủy thành công.
- Order có `status = 'cancelled'`.
- Stock Laptop trở lại `5`, không phải `9`.
- Cửa sổ B không hoàn stock lần thứ hai.

> `pg_sleep` chỉ dùng để quan sát lock trong bài lab, không đặt vào procedure production.

---

## 9. Checklist tự chấm

### Bài 1 — Tạo order

- [ ] Đúng tên procedure và kiểu tham số.
- [ ] Validate `NULL`, quantity nhỏ hơn `1` và lớn hơn `100`.
- [ ] Chỉ chọn product active.
- [ ] Có `SELECT ... INTO ... FOR UPDATE`.
- [ ] Test hai session chứng minh không oversell khi tổng số lượng đặt lớn hơn stock.
- [ ] Có `IF NOT FOUND`.
- [ ] Không cho stock âm.
- [ ] Update stock, đổi `updated_at` và insert order đúng giá.
- [ ] Có `RETURNING id INTO v_order_id`.
- [ ] Có `RAISE NOTICE` đủ thông tin.
- [ ] Không có `COMMIT`, `ROLLBACK` hoặc `WHEN OTHERS`.
- [ ] Tất cả test ở mục 4–7 cho kết quả đúng.

### Bài 2 — Hủy order

- [ ] Validate `p_order_id IS NULL`.
- [ ] Khóa order bằng `SELECT ... FOR UPDATE` trước khi kiểm tra status.
- [ ] Có `IF NOT FOUND` ngay sau `SELECT`.
- [ ] Chỉ cho phép chuyển `created` thành `cancelled`.
- [ ] Hoàn đúng quantity vào product và cập nhật `updated_at`.
- [ ] Có `UPDATE ... RETURNING name, stock INTO ...`.
- [ ] Lần hủy thứ hai báo lỗi và không làm tăng stock.
- [ ] Test hai session chứng minh stock chỉ được hoàn một lần.
- [ ] Không có `COMMIT`, `ROLLBACK` hoặc `WHEN OTHERS`.
- [ ] Tất cả test ở mục 8 cho kết quả đúng.

Đối chiếu sau khi tự làm: [Đáp án thực hành 1](1-postgresql-procedure-bai-tap-dap-an.md).

Lý thuyết liên quan: [PROCEDURE trong PostgreSQL — Chi tiết đầy đủ](../postgresql-procedure-chi-tiet.md).
