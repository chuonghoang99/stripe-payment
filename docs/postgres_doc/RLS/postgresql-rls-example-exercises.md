# PostgreSQL RLS — Example & Exercises with Answers

## 1. Example Schema

### Create roles

```sql
CREATE ROLE tenant_a LOGIN PASSWORD 'tenant_a_password';
CREATE ROLE tenant_b LOGIN PASSWORD 'tenant_b_password';
CREATE ROLE app_admin LOGIN PASSWORD 'admin_password';
```

> Lưu ý: Không dùng superuser để test RLS vì superuser có thể bypass RLS.

---

### Create table

```sql
CREATE TABLE orders (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    customer_name TEXT NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    status TEXT NOT NULL
);
```

Insert sample data:

```sql
INSERT INTO orders (tenant_id, customer_name, amount, status)
VALUES
    ('tenant_a', 'Alice',   1000, 'PENDING'),
    ('tenant_a', 'Bob',     2000, 'COMPLETED'),
    ('tenant_b', 'Charlie', 3000, 'PENDING'),
    ('tenant_b', 'David',   4000, 'COMPLETED');
```

---

## 2. Grant Table Privileges

```sql
GRANT USAGE ON SCHEMA schema1 TO tenant_a;
GRANT USAGE ON SCHEMA schema1 TO tenant_b;
    
GRANT SELECT, INSERT, UPDATE, DELETE
ON orders
TO tenant_a, tenant_b;
```

Nếu dùng identity sequence, PostgreSQL quản lý identity cho column nên application không cần tự gọi sequence trực tiếp trong ví dụ này.

---

## 3. Enable RLS

```sql
ALTER TABLE orders
ENABLE ROW LEVEL SECURITY;
```

---

## 4. Create RLS Policy

Trong ví dụ này:

```text
PostgreSQL role name = tenant_id
```

Ví dụ:

```text
current_user = tenant_a
→ chỉ được access row có tenant_id = 'tenant_a'
```

Policy:

```sql
CREATE POLICY orders_tenant_policy
ON orders
FOR ALL
TO tenant_a, tenant_b

USING (
    tenant_id = current_user
)

WITH CHECK (
    tenant_id = current_user
);
```

---

## 5. Test SELECT

Chuyển sang role `tenant_a`:

```sql
SET ROLE tenant_a;
```

Query:

```sql
SELECT *
FROM orders;
```

Expected result:

```text
┌────┬───────────┬───────────────┬────────┬───────────┐
│ id │ tenant_id │ customer_name │ amount │ status    │
├────┼───────────┼───────────────┼────────┼───────────┤
│ 1  │ tenant_a  │ Alice         │ 1000   │ PENDING   │
│ 2  │ tenant_a  │ Bob           │ 2000   │ COMPLETED │
└────┴───────────┴───────────────┴────────┴───────────┘
```

Mặc dù query không có:

```sql
WHERE tenant_id = 'tenant_a'
```

RLS vẫn tự giới hạn dữ liệu.

Reset:

```sql
RESET ROLE;
```

---

## 6. Test INSERT

```sql
SET ROLE tenant_a;
```

### Valid

```sql
INSERT INTO orders (
    tenant_id,
    customer_name,
    amount,
    status
)
VALUES (
    'tenant_a',
    'Emma',
    5000,
    'PENDING'
);
```

Kết quả:

```text
SUCCESS
```

### Invalid

```sql
INSERT INTO orders (
    tenant_id,
    customer_name,
    amount,
    status
)
VALUES (
    'tenant_b',
    'Hacker',
    9999,
    'PENDING'
);
```

Expected:

```text
ERROR:
new row violates row-level security policy
```

Nguyên nhân:

```text
WITH CHECK

tenant_id = current_user

tenant_b = tenant_a
→ FALSE
```

---

## 7. Test UPDATE

Giả sử `tenant_a` chạy:

```sql
UPDATE orders
SET amount = 9999
WHERE tenant_id = 'tenant_b';
```

Kết quả:

```text
UPDATE 0
```

Không phải vì row `tenant_b` không tồn tại.

Mà vì đối với `tenant_a`, những row đó bị RLS làm cho không visible/targetable.

---

### Attempt to move row to another tenant

```sql
UPDATE orders
SET tenant_id = 'tenant_b'
WHERE id = 1;
```

Flow:

```text
Old row:
tenant_id = tenant_a

USING
tenant_a = tenant_a
→ TRUE

              ↓ UPDATE

New row:
tenant_id = tenant_b

WITH CHECK
tenant_b = tenant_a
→ FALSE
```

Expected:

```text
ERROR:
new row violates row-level security policy
```

---

# Exercises

## Exercise 1 — SELECT Isolation

Table:

```text
orders
┌────┬───────────┬────────┐
│ id │ tenant_id │ amount │
├────┼───────────┼────────┤
│ 1  │ tenant_a  │ 100    │
│ 2  │ tenant_a  │ 200    │
│ 3  │ tenant_b  │ 300    │
│ 4  │ tenant_b  │ 400    │
└────┴───────────┴────────┘
```

Policy:

```sql
CREATE POLICY tenant_policy
ON orders
USING (
    tenant_id = current_user
);
```

Current user:

```text
tenant_a
```

Query:

```sql
SELECT *
FROM orders;
```

### Question

Có bao nhiêu row được trả về?

---

## Exercise 2 — USING

Policies:

```sql
CREATE POLICY tenant_select_policy
ON orders
FOR SELECT
USING (
    tenant_id = current_user
);

CREATE POLICY tenant_update_policy
ON orders
FOR UPDATE
USING (
    tenant_id = current_user
);
```

`UPDATE` phải đọc row hiện tại, đặc biệt câu lệnh bên dưới còn đọc cột `amount`
ở vế phải. Vì vậy PostgreSQL áp dụng cả policy `SELECT` và policy `UPDATE`.

Current user:

```text
tenant_a
```

Query:

```sql
UPDATE orders
SET amount = amount * 2;
```

### Question

Những row nào được update?

### Biến thể độc lập dùng `current_setting`

Trong ứng dụng thực tế, nhiều tenant có thể dùng chung một PostgreSQL role.
Khi đó application có thể truyền tenant hiện tại qua một custom setting thay vì
dựa vào `current_user`.

> Chạy biến thể này thay cho các policy dựa vào `current_user`, không chạy đồng
> thời. Nhiều permissive policy cùng loại được PostgreSQL kết hợp bằng `OR`, có
> thể vô tình mở rộng phạm vi tenant. Các lệnh `DROP POLICY` dưới đây chỉ dành
> cho bảng lab.

Trong lab, dùng `app_admin` như một shared runtime role thông thường. Role này
không được là table owner, superuser hoặc có thuộc tính `BYPASSRLS`.

Nếu bảng lab nằm trong schema `schema1`, chạy bằng owner/superuser:

```sql
DROP POLICY IF EXISTS orders_tenant_policy
ON schema1.orders;

DROP POLICY IF EXISTS tenant_policy
ON schema1.orders;

DROP POLICY IF EXISTS tenant_select_policy
ON schema1.orders;

DROP POLICY IF EXISTS tenant_update_policy
ON schema1.orders;

DROP POLICY IF EXISTS tenant_setting_select_policy
ON schema1.orders;

DROP POLICY IF EXISTS tenant_setting_update_policy
ON schema1.orders;

GRANT USAGE ON SCHEMA schema1 TO app_admin;

GRANT SELECT, UPDATE
ON TABLE schema1.orders
TO app_admin;

ALTER TABLE schema1.orders
ENABLE ROW LEVEL SECURITY;
```

Sau đó tạo các policy mới:

```sql
CREATE POLICY tenant_setting_select_policy
ON schema1.orders
FOR SELECT
TO app_admin
USING (
    tenant_id = NULLIF(
        current_setting('app.current_tenant', true),
        ''
    )
);

CREATE POLICY tenant_setting_update_policy
ON schema1.orders
FOR UPDATE
TO app_admin
USING (
    tenant_id = NULLIF(
        current_setting('app.current_tenant', true),
        ''
    )
);
```

Thiết lập tenant trong phạm vi transaction rồi chạy câu lệnh:

```sql
SET ROLE app_admin;

BEGIN;

SELECT set_config(
    'app.current_tenant',
    'tenant_a',
    true
);

UPDATE schema1.orders
SET amount = amount * 2;

COMMIT;

RESET ROLE;
```

Trong `set_config`, tham số thứ ba là `true` làm setting chỉ tồn tại trong
transaction hiện tại. Trong `current_setting`, tham số thứ hai là `true` làm
PostgreSQL trả về `NULL` thay vì báo lỗi nếu setting không tồn tại. `NULLIF`
chuẩn hóa cả giá trị rỗng có thể còn lại trên một pooled session thành `NULL`,
nhờ đó policy không match tenant nào khi thiếu tenant context.

> Lưu ý: custom setting không tự xác thực tenant. Application phải lấy tenant từ
> identity đã được xác thực và truyền giá trị bằng parameter binding, không nhận
> trực tiếp một giá trị tenant tùy ý từ client. Không dùng mô hình này nếu client
> có thể kết nối database và tự chạy `set_config` bằng runtime role.

### Question bổ sung

- Những row nào được update khi `app.current_tenant = 'tenant_a'`?
- Điều gì xảy ra nếu application chưa thiết lập `app.current_tenant`?

---

## Exercise 3 — WITH CHECK

Policy:

```sql
CREATE POLICY tenant_insert
ON orders
FOR INSERT
WITH CHECK (
    tenant_id = current_user
);
```

Current user:

```text
tenant_a
```

Query:

```sql
INSERT INTO orders (
    tenant_id,
    customer_name,
    amount,
    status
)
VALUES (
    'tenant_b',
    'Test',
    100,
    'PENDING'
);
```

### Question

Query có thành công không? Vì sao?

---

## Exercise 4 — RLS + GRANT

Bạn đã tạo:

```sql
ALTER TABLE orders
ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_policy
ON orders
USING (
    tenant_id = current_user
);
```

Nhưng role:

```text
tenant_a
```

chưa được:

```sql
GRANT SELECT ON orders TO tenant_a;
```

### Question

`tenant_a` có SELECT được dữ liệu không?

---

## Exercise 5 — Default Deny

Bạn chạy:

```sql
ALTER TABLE orders
ENABLE ROW LEVEL SECURITY;
```

nhưng chưa tạo bất kỳ policy nào.

Role `tenant_a` có `SELECT` privilege trên table.

### Question

Khi chạy:

```sql
SELECT *
FROM orders;
```

kết quả sẽ thế nào?

---

## Exercise 6 — Multiple Permissive Policies

Có hai policy:

```sql
CREATE POLICY own_tenant
ON orders
FOR SELECT
USING (
    tenant_id = current_user
);
```

và:

```sql
CREATE POLICY high_value_orders
ON orders
FOR SELECT
USING (
    amount >= 10000
);
```

Cả hai đều là policy mặc định `PERMISSIVE`.

### Question

Hai policy được kết hợp theo `AND` hay `OR`?

---

## Exercise 7 — Restrictive Policy

Bạn có:

```sql
CREATE POLICY tenant_access
ON orders
AS PERMISSIVE
FOR SELECT
USING (
    tenant_id = current_user
);
```

và:

```sql
CREATE POLICY only_active_orders
ON orders
AS RESTRICTIVE
FOR SELECT
USING (
    status <> 'DELETED'
);
```

### Question

Một row phải thỏa điều kiện gì để được trả về?

---

## Exercise 8 — Superuser

RLS:

```sql
ALTER TABLE orders
ENABLE ROW LEVEL SECURITY;
```

Policy:

```sql
CREATE POLICY tenant_policy
ON orders
USING (
    tenant_id = current_user
);
```

Superuser chạy:

```sql
SELECT *
FROM orders;
```

### Question

Superuser có bị giới hạn bởi policy không?

---

## Exercise 9 — Table Owner

Owner của table `orders` chạy:

```sql
SELECT *
FROM orders;
```

RLS đã được enable.

### Question

Owner có luôn chịu RLS không?

Nếu muốn buộc owner chịu RLS thì dùng câu lệnh gì?

---

## Exercise 10 — Design Policy

Yêu cầu:

- User chỉ được đọc orders của tenant mình.
- User chỉ được INSERT vào tenant mình.
- User không được UPDATE `tenant_id` sang tenant khác.

Hãy viết policy phù hợp.

---

# Answers

## Answer 1

Có:

```text
2 rows
```

Là:

```text
tenant_a / 100
tenant_a / 200
```

RLS conceptually bổ sung:

```sql
WHERE tenant_id = current_user
```

---

## Answer 2

Chỉ những row:

```text
tenant_id = tenant_a
```

được update.

Conceptually:

```sql
UPDATE orders
SET amount = amount * 2
WHERE tenant_id = 'tenant_a';
```

RLS không thực sự rewrite query theo kiểu string replacement, nhưng đây là mental model dễ hiểu.

Với biến thể dùng `current_setting`, câu lệnh:

```sql
SELECT set_config('app.current_tenant', 'tenant_a', true);
```

làm điều kiện `USING` tương đương:

```sql
tenant_id = 'tenant_a'
```

Vì vậy chỉ các row của `tenant_a` được update. Nếu `app.current_tenant` không
tồn tại hoặc có giá trị rỗng, biểu thức `NULLIF` trả về `NULL`, điều kiện `USING`
không đúng với row nào và câu lệnh update `0 rows`.

Việc đặt setting theo transaction còn tránh tenant context của request trước bị
tái sử dụng nhầm khi application dùng connection pool.

---

## Answer 3

Không thành công.

Vì:

```sql
WITH CHECK (
    tenant_id = current_user
)
```

và:

```text
tenant_b != tenant_a
```

PostgreSQL reject row mới.

---

## Answer 4

Không.

RLS không thay thế `GRANT`.

Security flow:

```text
Query
  ↓
GRANT / Privilege
  ↓
RLS
  ↓
Data
```

Nếu không có table privilege thì request bị từ chối trước.

---

## Answer 5

Không row nào được trả về.

RLS sử dụng nguyên tắc:

```text
RLS enabled
+
No applicable policy
=
Default Deny
```

---

## Answer 6

Các permissive policies được kết hợp theo:

```text
OR
```

Conceptually:

```sql
tenant_id = current_user
OR
amount >= 10000
```

---

## Answer 7

Row phải thỏa:

```text
(ít nhất một permissive policy)
AND
(tất cả restrictive policies)
```

Trong ví dụ:

```sql
tenant_id = current_user
AND
status <> 'DELETED'
```

---

## Answer 8

Thông thường:

```text
Superuser → bypass RLS
```

Do đó không nên dùng superuser để kiểm thử behavior của RLS cho application role.

---

## Answer 9

Table owner thường bypass RLS.

Để buộc owner chịu RLS:

```sql
ALTER TABLE orders
FORCE ROW LEVEL SECURITY;
```

---

## Answer 10

Có thể dùng một policy `FOR ALL`:

```sql
CREATE POLICY tenant_isolation
ON orders
FOR ALL

USING (
    tenant_id = current_user
)

WITH CHECK (
    tenant_id = current_user
);
```

Ý nghĩa:

```text
USING
→ chỉ access/update/delete row của tenant hiện tại

WITH CHECK
→ row mới sau INSERT/UPDATE vẫn phải thuộc tenant hiện tại
```

Do đó:

```sql
UPDATE orders
SET tenant_id = 'tenant_b'
WHERE tenant_id = 'tenant_a';
```

sẽ bị reject khi current user là `tenant_a`.

---

# Bonus — Inspect RLS Policies

Xem policy của table:

```sql
SELECT
    schemaname,
    tablename,
    policyname,
    permissive,
    roles,
    cmd,
    qual,
    with_check
FROM pg_policies
WHERE tablename = 'orders';
```

Kiểm tra trạng thái RLS:

```sql
SELECT
    relname,
    relrowsecurity,
    relforcerowsecurity
FROM pg_class
WHERE relname = 'orders';
```

Ý nghĩa:

```text
relrowsecurity
→ RLS có được ENABLE hay không

relforcerowsecurity
→ FORCE ROW LEVEL SECURITY có bật hay không
```

---

# Quick Summary

```text
ENABLE ROW LEVEL SECURITY
           │
           ↓
        POLICY
       /      \
   USING     WITH CHECK
     │           │
Existing rows   New rows
     │           │
SELECT          INSERT
UPDATE          UPDATE
DELETE
```

Và luôn nhớ:

```text
GRANT
→ được access table?

RLS
→ được access row nào?
```
