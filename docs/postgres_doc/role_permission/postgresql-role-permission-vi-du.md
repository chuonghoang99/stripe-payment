# Role & Permission trong PostgreSQL — Ví dụ thực chiến

> File 2/2 — Ví dụ cụ thể. Xem lý thuyết đầy đủ tại `postgresql-role-permission-ly-thuyet.md`.
> Domain xuyên suốt: hệ thống bán hàng (`customers`, `products`, `orders`, `order_items`).
> Mức độ bài tập cuối file: 🟢 Cơ bản — 🟡 Trung bình — 🔴 Nâng cao — ⚫ Chuyên sâu.

---

## Chuẩn bị bảng dùng chung cho toàn bộ ví dụ

```sql
CREATE TABLE customers (
    id serial PRIMARY KEY,
    full_name text NOT NULL,
    email text UNIQUE NOT NULL,
    national_id text,          -- cột nhạy cảm, dùng cho ví dụ column-level privilege
    tenant_id int NOT NULL,    -- dùng cho ví dụ RLS
    created_at timestamptz DEFAULT now()
);

CREATE TABLE products (
    id serial PRIMARY KEY,
    name text NOT NULL,
    price numeric(12,2) NOT NULL,
    stock int NOT NULL DEFAULT 0
);

CREATE TABLE orders (
    id serial PRIMARY KEY,
    customer_id int REFERENCES customers(id),
    tenant_id int NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    created_at timestamptz DEFAULT now()
);
```

---

## 1. Tạo Role cơ bản với các thuộc tính khác nhau

```sql
-- Role đăng nhập được, có mật khẩu, giới hạn 10 kết nối đồng thời
CREATE ROLE alice WITH
    LOGIN
    PASSWORD 'Str0ngP@ss!'
    CONNECTION LIMIT 10
    VALID UNTIL '2027-01-01';

-- Role dùng làm "group", không login được
CREATE ROLE readonly_group WITH NOLOGIN;

-- Role cho migration tool, có quyền tạo/sửa object nhưng không phải superuser
CREATE ROLE migration_role WITH LOGIN PASSWORD 'MigrationPass1' CREATEROLE;

-- Kiểm tra lại thuộc tính vừa tạo
\du alice
```

Đổi thuộc tính sau khi đã tạo:

```sql
ALTER ROLE alice WITH CONNECTION LIMIT 20;
ALTER ROLE alice PASSWORD 'NewP@ssword2';
ALTER ROLE alice VALID UNTIL '2028-01-01';
ALTER ROLE alice NOLOGIN;   -- tạm khóa đăng nhập mà không xóa role
```

---

## 2. Database → Schema → Object — cấp đủ 3 tầng

Đây là ví dụ minh họa lỗi runtime phổ biến nhất và cách khắc phục đúng thứ tự.

```sql
-- Bước 0: tạo role ứng dụng
CREATE ROLE app_user WITH LOGIN PASSWORD 'AppUserPass1';

-- ❌ Nếu chỉ làm bước này rồi kết nối bằng app_user và SELECT → lỗi "permission denied for schema public"
GRANT SELECT ON customers TO app_user;

-- ✅ Làm đúng thứ tự đủ 3 tầng:
GRANT CONNECT ON DATABASE interview_lab TO app_user;      -- tầng 1: Database
GRANT USAGE ON SCHEMA public TO app_user;                 -- tầng 2: Schema
GRANT SELECT, INSERT, UPDATE ON customers TO app_user;    -- tầng 3: Object
```

**Thử nghiệm chứng minh:** mở session mới bằng `psql -U app_user -d interview_lab`, chạy `SELECT * FROM customers;` — nếu thiếu bước `GRANT USAGE ON SCHEMA` sẽ báo lỗi ngay dù đã grant SELECT.

---

## 3. Lỗi kinh điển: quên quyền trên SEQUENCE khi INSERT vào bảng có cột serial

```sql
CREATE ROLE order_writer WITH LOGIN PASSWORD 'WriterPass1';

GRANT CONNECT ON DATABASE interview_lab TO order_writer;
GRANT USAGE ON SCHEMA public TO order_writer;
GRANT INSERT ON orders TO order_writer;

-- ❌ Vẫn lỗi khi INSERT vì cột id dùng serial (cần USAGE trên sequence backing nó)
-- ERROR: permission denied for sequence orders_id_seq

-- ✅ Cấp thêm quyền trên sequence:
GRANT USAGE, SELECT ON SEQUENCE orders_id_seq TO order_writer;

-- Hoặc cấp hàng loạt cho mọi sequence hiện có trong schema:
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO order_writer;
```

---

## 4. Role nhóm (group role) — gom quyền, gán nhiều user

```sql
-- Tạo group role không login
CREATE ROLE dev_team WITH NOLOGIN;

-- Cấp quyền cho group 1 lần duy nhất
GRANT CONNECT ON DATABASE interview_lab TO dev_team;
GRANT USAGE ON SCHEMA public TO dev_team;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO dev_team;

-- Tạo user thật, gán vào group thay vì cấp quyền lẻ tẻ
CREATE ROLE bob WITH LOGIN PASSWORD 'BobPass1';
CREATE ROLE carol WITH LOGIN PASSWORD 'CarolPass1';

GRANT dev_team TO bob;
GRANT dev_team TO carol;

-- Khi cần thêm 1 quyền mới cho cả team, chỉ cần GRANT 1 lần cho dev_team,
-- bob và carol tự động có quyền đó (nhờ cơ chế INHERIT mặc định)
```

Thu hồi quyền của 1 người mà không ảnh hưởng người khác trong group:

```sql
REVOKE dev_team FROM carol;   -- carol mất toàn bộ quyền thừa hưởng từ dev_team
-- bob vẫn giữ nguyên quyền vì vẫn là thành viên
```

---

## 5. `NOINHERIT` — kích hoạt quyền thủ công bằng `SET ROLE`

```sql
CREATE ROLE admin_group WITH NOLOGIN;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO admin_group;

-- dave có quyền admin nhưng KHÔNG tự động kích hoạt (an toàn hơn, tránh thao tác nhầm)
CREATE ROLE dave WITH LOGIN PASSWORD 'DavePass1' NOINHERIT;
GRANT admin_group TO dave;
```

Khi đăng nhập bằng `dave` và thử:

```sql
DELETE FROM orders WHERE id = 1;
-- ❌ ERROR: permission denied for table orders (vì NOINHERIT, quyền admin_group chưa kích hoạt)

SET ROLE admin_group;
DELETE FROM orders WHERE id = 1;
-- ✅ Chạy được, vì đã chủ động kích hoạt quyền admin_group

RESET ROLE;
-- quay lại quyền gốc của dave (không có DELETE nữa)
```

→ Đây là kỹ thuật hữu ích để **giảm rủi ro thao tác nhầm** với quyền cao — buộc phải "xác nhận" bằng `SET ROLE` mới thực thi được.

---

## 6. `ALTER DEFAULT PRIVILEGES` — tự động cấp quyền cho bảng tạo sau này

```sql
CREATE ROLE app_owner WITH LOGIN PASSWORD 'OwnerPass1' CREATEDB;
CREATE ROLE readonly_report WITH NOLOGIN;

GRANT CONNECT ON DATABASE interview_lab TO readonly_report;
GRANT USAGE ON SCHEMA public TO readonly_report;

-- Cấp SELECT cho các bảng ĐÃ tồn tại (bước này không tự động áp dụng cho bảng tương lai)
GRANT SELECT ON ALL TABLES IN SCHEMA public TO readonly_report;

-- Khai báo: từ giờ, bảng nào app_owner tạo trong schema public,
-- tự động cấp SELECT cho readonly_report — không cần GRANT tay nữa
ALTER DEFAULT PRIVILEGES
FOR ROLE app_owner
IN SCHEMA public
GRANT SELECT ON TABLES TO readonly_report;
```

**Kiểm chứng:**

```sql
-- Đăng nhập bằng app_owner, tạo bảng mới
SET ROLE app_owner;
CREATE TABLE shipments (id serial PRIMARY KEY, order_id int, shipped_at timestamptz);
RESET ROLE;

-- Đăng nhập bằng readonly_report, kiểm tra
SET ROLE readonly_report;
SELECT * FROM shipments;   -- ✅ Chạy được ngay, không cần GRANT thủ công
RESET ROLE;
```

⚠️ Nếu bảng `shipments` được tạo bởi role khác (không phải `app_owner`), `readonly_report` **sẽ không** tự động có quyền — vì `ALTER DEFAULT PRIVILEGES` chỉ áp dụng đúng cho `FOR ROLE` đã khai báo.

Áp dụng tương tự cho function/sequence:

```sql
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA public
GRANT EXECUTE ON FUNCTIONS TO readonly_report;

ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA public
GRANT USAGE, SELECT ON SEQUENCES TO readonly_report;
```

---

## 7. Thiết kế phân quyền 3 tầng thực tế (Least Privilege)

```sql
-- ===== Tầng 1: admin =====
CREATE ROLE admin_role WITH LOGIN PASSWORD 'AdminPass1' CREATEDB CREATEROLE;

-- ===== Tầng 2: app_service (CRUD giới hạn, không DROP/TRUNCATE) =====
CREATE ROLE app_service WITH LOGIN PASSWORD 'ServicePass1';
GRANT CONNECT ON DATABASE interview_lab TO app_service;
GRANT USAGE ON SCHEMA public TO app_service;
GRANT SELECT, INSERT, UPDATE, DELETE ON customers, products, orders TO app_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_service;
-- KHÔNG cấp TRUNCATE, KHÔNG cấp quyền trên DDL (CREATE/ALTER/DROP)

-- ===== Tầng 3: analyst (chỉ đọc, qua view báo cáo, không đọc bảng gốc) =====
CREATE ROLE analyst WITH LOGIN PASSWORD 'AnalystPass1';
GRANT CONNECT ON DATABASE interview_lab TO analyst;
GRANT USAGE ON SCHEMA public TO analyst;

CREATE VIEW v_order_summary AS
SELECT o.id, o.status, c.full_name, o.created_at
FROM orders o JOIN customers c ON c.id = o.customer_id;

GRANT SELECT ON v_order_summary TO analyst;
-- analyst KHÔNG có SELECT trực tiếp trên customers/orders — chỉ qua view đã lọc cột cần thiết
```

Kiểm chứng `analyst` bị chặn khi thử truy cập bảng gốc:

```sql
SET ROLE analyst;
SELECT * FROM customers;
-- ❌ ERROR: permission denied for table customers

SELECT * FROM v_order_summary;
-- ✅ Chạy được
RESET ROLE;
```

---

## 8. Column-level Privilege — che cột nhạy cảm

```sql
CREATE ROLE support_staff WITH LOGIN PASSWORD 'SupportPass1';
GRANT CONNECT ON DATABASE interview_lab TO support_staff;
GRANT USAGE ON SCHEMA public TO support_staff;

-- Chỉ cấp quyền SELECT trên các cột KHÔNG nhạy cảm
GRANT SELECT (id, full_name, email, created_at) ON customers TO support_staff;

-- Chỉ cho phép sửa cột status của orders, không sửa được các cột khác
GRANT UPDATE (status) ON orders TO support_staff;
```

Kiểm chứng:

```sql
SET ROLE support_staff;

SELECT id, full_name, email FROM customers;   -- ✅ OK
SELECT national_id FROM customers;            -- ❌ ERROR: permission denied for table customers (cột)
SELECT * FROM customers;                      -- ❌ ERROR: vì SELECT * bao gồm cả national_id

UPDATE orders SET status = 'shipped' WHERE id = 1;        -- ✅ OK
UPDATE orders SET customer_id = 5 WHERE id = 1;           -- ❌ ERROR: permission denied (cột customer_id)

RESET ROLE;
```

---

## 9. Row Level Security (RLS) — multi-tenant theo `tenant_id`

```sql
-- Bật RLS trên bảng orders
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

-- Policy cho SELECT: chỉ thấy đơn hàng của tenant hiện tại
CREATE POLICY tenant_select ON orders
FOR SELECT
USING (tenant_id = current_setting('app.current_tenant')::int);

-- Policy cho INSERT: chỉ được chèn đơn hàng cho tenant của chính mình
CREATE POLICY tenant_insert ON orders
FOR INSERT
WITH CHECK (tenant_id = current_setting('app.current_tenant')::int);

-- Policy cho UPDATE: chỉ sửa được đơn hàng thuộc tenant mình, và không đổi sang tenant khác
CREATE POLICY tenant_update ON orders
FOR UPDATE
USING (tenant_id = current_setting('app.current_tenant')::int)
WITH CHECK (tenant_id = current_setting('app.current_tenant')::int);

-- Role dùng chung cho ứng dụng multi-tenant
CREATE ROLE tenant_app WITH LOGIN PASSWORD 'TenantAppPass1';
GRANT CONNECT ON DATABASE interview_lab TO tenant_app;
GRANT USAGE ON SCHEMA public TO tenant_app;
GRANT SELECT, INSERT, UPDATE ON orders TO tenant_app;
```

**Kiểm chứng cách ly dữ liệu giữa 2 tenant:**

```sql
SET ROLE tenant_app;

SET app.current_tenant = '1';
SELECT * FROM orders;   -- chỉ thấy đơn hàng tenant_id = 1

SET app.current_tenant = '2';
SELECT * FROM orders;   -- chỉ thấy đơn hàng tenant_id = 2, KHÔNG thấy dữ liệu tenant 1

-- Thử insert nhầm tenant khác:
SET app.current_tenant = '1';
INSERT INTO orders(customer_id, tenant_id, status) VALUES (1, 2, 'pending');
-- ❌ ERROR: new row violates row-level security policy "tenant_insert"

RESET ROLE;
```

### Cho phép admin xem toàn bộ dữ liệu bất kể tenant (bypass có kiểm soát)

```sql
-- Cách 1: dùng thuộc tính BYPASSRLS (bỏ qua toàn bộ RLS trên mọi bảng)
ALTER ROLE admin_role BYPASSRLS;

-- Cách 2: FORCE RLS + policy riêng cho admin (kiểm soát chặt hơn, khuyến nghị)
ALTER TABLE orders FORCE ROW LEVEL SECURITY;  -- áp cả cho owner

CREATE POLICY admin_full_access ON orders
FOR ALL
TO admin_role
USING (true)
WITH CHECK (true);
```

---

## 10. `SECURITY DEFINER` kết hợp Role/Permission — báo cáo tổng hợp không cần cấp quyền bảng gốc

```sql
CREATE OR REPLACE FUNCTION get_daily_revenue()
RETURNS TABLE(report_date date, total_revenue numeric)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT date_trunc('day', o.created_at)::date, SUM(oi.qty * oi.unit_price)
    FROM orders o
    JOIN order_items oi ON oi.order_id = o.id
    GROUP BY 1 ORDER BY 1;
$$;

-- Chỉ cấp EXECUTE, KHÔNG cấp SELECT trực tiếp trên orders/order_items
REVOKE EXECUTE ON FUNCTION get_daily_revenue() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION get_daily_revenue() TO analyst;
```

Kiểm chứng:

```sql
SET ROLE analyst;

SELECT * FROM orders;              -- ❌ ERROR: permission denied
SELECT * FROM get_daily_revenue(); -- ✅ Chạy được — function chạy với quyền của người tạo (owner)

RESET ROLE;
```

---

## 11. `REVOKE EXECUTE FROM PUBLIC` — khắc phục lỗ hổng mặc định

```sql
CREATE OR REPLACE FUNCTION reset_all_passwords() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp
AS $$ BEGIN /* logic nhạy cảm */ END; $$;

-- ⚠️ Mặc định PUBLIC đã tự động có EXECUTE ngay sau khi tạo — RỦI RO nếu bỏ qua bước dưới
REVOKE EXECUTE ON FUNCTION reset_all_passwords() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reset_all_passwords() TO admin_role;
```

Kiểm chứng trước/sau khi revoke bằng 1 role thường bất kỳ:

```sql
SET ROLE bob;
SELECT reset_all_passwords();
-- Trước REVOKE: ✅ chạy được (rủi ro!)
-- Sau REVOKE:   ❌ ERROR: permission denied for function reset_all_passwords
RESET ROLE;
```

---

## 12. `REASSIGN OWNED` / `DROP OWNED` — chuẩn bị trước khi xóa Role

```sql
-- Nếu carol sở hữu object (bảng, view, function do carol tạo), KHÔNG thể DROP trực tiếp:
DROP ROLE carol;
-- ❌ ERROR: role "carol" cannot be dropped because some objects depend on it

-- Bước 1: chuyển quyền sở hữu toàn bộ object của carol sang role khác
REASSIGN OWNED BY carol TO admin_role;

-- Bước 2: xóa toàn bộ quyền còn lại (GRANT) mà carol đang giữ trên các object khác
DROP OWNED BY carol;

-- Bước 3: giờ mới xóa được role
DROP ROLE carol;
```

---

## 13. Introspection — kiểm tra quyền thực tế

```sql
-- Xem tất cả role và thuộc tính
\du

-- Xem quyền trên 1 bảng cụ thể (ai có quyền gì)
\dp orders

-- Query catalog: liệt kê toàn bộ quyền GRANT trên bảng orders
SELECT grantee, privilege_type
FROM information_schema.role_table_grants
WHERE table_name = 'orders';

-- Kiểm tra 1 role cụ thể có quyền SELECT trên bảng hay không
SELECT has_table_privilege('app_service', 'orders', 'SELECT');

-- Kiểm tra quyền EXECUTE function
SELECT has_function_privilege('analyst', 'get_daily_revenue()', 'EXECUTE');

-- Xem cây membership: role nào thuộc group nào
SELECT r.rolname AS member, g.rolname AS member_of
FROM pg_auth_members m
JOIN pg_roles r ON m.member = r.oid
JOIN pg_roles g ON m.roleid = g.oid
ORDER BY g.rolname;

-- Xem toàn bộ policy RLS đang có trên 1 bảng
SELECT policyname, cmd, qual, with_check
FROM pg_policies
WHERE tablename = 'orders';
```

---

## 14. Bài tập thực hành (tăng dần độ khó)

- 🟢 Tạo role `app_user` chỉ có `SELECT/INSERT/UPDATE` trên `customers`, `orders` — không có `DELETE`. Kiểm chứng bằng cách thử `DELETE` và xác nhận bị từ chối.
- 🟢 Tạo role `readonly_report`, cấp `SELECT` toàn schema, kiểm chứng bằng `has_table_privilege()`.
- 🟡 Tái hiện lỗi "permission denied for sequence" (mục 3), sửa đúng cách bằng `GRANT USAGE, SELECT ON SEQUENCE`.
- 🟡 Tạo `dev_team` (group role), gán 2 user vào, chứng minh khi `REVOKE dev_team FROM 1 user`, người còn lại không bị ảnh hưởng.
- 🟡 Dùng `ALTER DEFAULT PRIVILEGES` để mọi bảng do `app_owner` tạo sau này tự động cấp `SELECT` cho `readonly_report`; kiểm chứng bằng cách tạo 1 bảng mới và query ngay bằng `readonly_report` mà không cần GRANT tay.
- 🔴 Thiết kế đủ 3 tầng `admin_role` / `app_service` / `analyst` như mục 7, kiểm chứng `analyst` bị chặn khi SELECT trực tiếp bảng gốc nhưng đọc được qua view.
- 🔴 Cài đặt RLS multi-tenant đầy đủ (SELECT + INSERT + UPDATE policy) như mục 9, kiểm chứng cách ly dữ liệu giữa 2 tenant bằng 2 lần `SET app.current_tenant` khác nhau trong cùng 1 session.
- 🔴 Kết hợp RLS với `FORCE ROW LEVEL SECURITY` + policy riêng cho `admin_role` để admin thấy toàn bộ dữ liệu bất kể tenant, trong khi user thường vẫn bị giới hạn.
- ⚫ Đo hiệu năng (`EXPLAIN ANALYZE`) 1 query trên bảng `orders` giả lập >1 triệu dòng, trước và sau khi bật RLS, sau đó tối ưu bằng cách tạo index trên `tenant_id`.
- ⚫ Viết kịch bản đầy đủ: tạo role `temp_contractor` có hạn dùng 30 ngày (`VALID UNTIL`), cấp quyền tối thiểu cần thiết, rồi thực hành quy trình xóa an toàn (`REASSIGN OWNED BY` → `DROP OWNED BY` → `DROP ROLE`) khi hết hạn hợp đồng.
