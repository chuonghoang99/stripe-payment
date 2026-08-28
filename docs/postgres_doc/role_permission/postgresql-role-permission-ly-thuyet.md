# Role & Permission trong PostgreSQL — Lý thuyết chi tiết

> File 1/2 — Lý thuyết. File 2/2 (`postgresql-role-permission-vi-du.md`) chứa ví dụ thực chiến theo domain bán hàng.

---

## 1. Khái niệm ROLE trong PostgreSQL

PostgreSQL **không phân biệt "user" và "role"** như nhiều hệ CSDL khác — từ PostgreSQL 8.1 trở đi, `USER` chỉ là **role có thuộc tính `LOGIN`**. Nói cách khác:

```
ROLE = khái niệm tổng quát (có thể đại diện cho 1 người dùng, hoặc 1 nhóm quyền)
USER = ROLE + LOGIN (có thể đăng nhập)
GROUP = ROLE không có LOGIN, dùng để gom quyền rồi gán cho nhiều role khác
```

```sql
CREATE USER alice;          -- tương đương CREATE ROLE alice WITH LOGIN;
CREATE ROLE readonly_group; -- không có LOGIN, dùng làm "group" chứa quyền
```

### Thuộc tính (attributes) của Role

| Thuộc tính | Ý nghĩa |
|---|---|
| `LOGIN` / `NOLOGIN` | Có được phép kết nối vào DB hay không |
| `SUPERUSER` / `NOSUPERUSER` | Bỏ qua mọi kiểm tra quyền (cực kỳ nguy hiểm, hạn chế dùng) |
| `CREATEDB` / `NOCREATEDB` | Có được tạo database mới |
| `CREATEROLE` / `NOCREATEROLE` | Có được tạo/sửa/xóa role khác |
| `INHERIT` / `NOINHERIT` | Có tự động thừa hưởng quyền từ role cha mà nó là thành viên hay không |
| `REPLICATION` / `NOREPLICATION` | Có được dùng cho streaming replication |
| `BYPASSRLS` / `NOBYPASSRLS` | Có bỏ qua Row Level Security hay không |
| `CONNECTION LIMIT n` | Giới hạn số kết nối đồng thời |
| `PASSWORD 'xxx'` | Mật khẩu (nên kết hợp `VALID UNTIL` để hết hạn) |
| `VALID UNTIL 'timestamp'` | Thời điểm role/password hết hiệu lực |

---

## 2. Object trong PostgreSQL cần cấp quyền

Quyền (`GRANT`/`REVOKE`) áp dụng lên nhiều loại object khác nhau, không chỉ table:

| Object | Các quyền phổ biến |
|---|---|
| `DATABASE` | `CONNECT`, `CREATE`, `TEMP/TEMPORARY` |
| `SCHEMA` | `USAGE`, `CREATE` |
| `TABLE` / `VIEW` | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER` |
| `SEQUENCE` | `USAGE`, `SELECT`, `UPDATE` |
| `FUNCTION` / `PROCEDURE` | `EXECUTE` |
| `COLUMN` (cấp quyền cấp cột) | `SELECT`, `INSERT`, `UPDATE` trên từng cột cụ thể |
| `LANGUAGE` | `USAGE` |
| `TYPE` | `USAGE` |
| `FOREIGN DATA WRAPPER` / `FOREIGN SERVER` | `USAGE` |
| `LARGE OBJECT` | `SELECT`, `UPDATE` |

### Cú pháp GRANT tổng quát

```sql
GRANT { quyền [, ...] | ALL [PRIVILEGES] }
ON { object_type object_name [, ...] }
TO role_name [, ...]
[WITH GRANT OPTION];
```

`WITH GRANT OPTION` cho phép role được cấp quyền lại **có thể tự cấp quyền đó cho role khác** — tương tự khái niệm "admin quyền" trong nhiều hệ thống phân quyền.

---

## 3. Phân cấp quyền cần hiểu: Database → Schema → Object

Đây là điểm **rất hay bị hiểu sai/bỏ sót** trong thực tế: cấp quyền `SELECT` trên table là **chưa đủ** nếu role chưa có quyền ở tầng cao hơn.

```
Kết nối vào database  → cần CONNECT trên DATABASE
    ↓
Nhìn thấy/dùng schema → cần USAGE trên SCHEMA
    ↓
Thao tác trên object  → cần SELECT/INSERT/... trên TABLE/VIEW/...
```

Thiếu bất kỳ tầng nào ở trên, dù đã `GRANT SELECT` ở tầng dưới cùng, user vẫn **không truy vấn được** — đây là lỗi runtime "permission denied for schema" rất phổ biến với người mới.

```sql
-- Thiếu bước này thì dù GRANT SELECT ON table cũng vô nghĩa:
GRANT USAGE ON SCHEMA public TO app_user;
```

---

## 4. `PUBLIC` — role đặc biệt, mọi role đều là thành viên

`PUBLIC` không phải 1 role thật, mà là **pseudo-role đại diện cho "tất cả mọi role"**. Cấp quyền cho `PUBLIC` nghĩa là cấp cho toàn bộ hệ thống.

Một số quyền mặc định **đã cấp sẵn cho PUBLIC** khi tạo object mới, dễ gây rủi ro bảo mật nếu không biết:

| Object | Quyền mặc định cho PUBLIC |
|---|---|
| `DATABASE` mới | `CONNECT`, `TEMP` |
| `FUNCTION`/`PROCEDURE` mới | `EXECUTE` |
| `LANGUAGE` (vd `plpgsql`) mặc định | `USAGE` |
| `TYPE` mới | `USAGE` |
| `SCHEMA public` (trước PG15) | `CREATE`, `USAGE` — **đã đổi từ PG15**: schema `public` không còn tự cấp `CREATE` cho PUBLIC nữa |

→ Best practice: sau khi tạo function/procedure nhạy cảm, nên chủ động `REVOKE EXECUTE ... FROM PUBLIC` rồi `GRANT` lại đúng role cần thiết.

---

## 5. Role kế thừa quyền qua Membership (`GRANT role TO role`)

PostgreSQL cho phép 1 role trở thành **thành viên** của role khác — đây là cơ chế dùng để làm "group" role.

```sql
GRANT readonly_group TO alice;
```

Câu lệnh trên **không phải** cấp quyền SELECT trực tiếp — mà làm `alice` trở thành thành viên của `readonly_group`. Nếu `readonly_group` có `SELECT` trên các bảng, `alice` **tự động thừa hưởng** các quyền đó — với điều kiện `alice` có thuộc tính `INHERIT` (mặc định là có từ PG16 trở về sau; các phiên bản cũ hơn mặc định cũng là INHERIT trừ khi tạo `NOINHERIT` tường minh).

### `INHERIT` vs `NOINHERIT` — khác biệt quan trọng

- **`INHERIT`** (mặc định): quyền của role cha **tự động** có hiệu lực, không cần làm gì thêm.
- **`NOINHERIT`**: phải chủ động `SET ROLE parent_role;` trong session để "kích hoạt" quyền của role cha — giống cơ chế `sudo` tạm thời, dùng khi muốn hạn chế quyền mặc định để tránh lỗi vô tình.

```sql
SET ROLE readonly_group;  -- chỉ cần thiết nếu alice là NOINHERIT
-- các câu query sau đó chạy với quyền của readonly_group
RESET ROLE;                -- quay lại quyền gốc của alice
```

---

## 6. `ALTER DEFAULT PRIVILEGES` — giải quyết vấn đề "bảng tạo sau không tự có quyền"

Đây là điểm **cực kỳ hay bị hỏi trong phỏng vấn thực chiến**. Theo mặc định, `GRANT SELECT ON table_x TO role_y` **chỉ áp dụng cho bảng đã tồn tại tại thời điểm chạy lệnh**. Bảng tạo mới sau đó **không tự động có quyền** — phải `GRANT` lại thủ công.

`ALTER DEFAULT PRIVILEGES` giải quyết vấn đề này bằng cách khai báo trước: "từ giờ, khi role X tạo object mới trong schema Y, tự động cấp quyền Z cho role W".

```sql
ALTER DEFAULT PRIVILEGES
FOR ROLE app_owner            -- áp dụng cho object do app_owner tạo ra
IN SCHEMA public
GRANT SELECT ON TABLES TO readonly_group;
```

⚠️ Lưu ý: `ALTER DEFAULT PRIVILEGES` chỉ ảnh hưởng đến object **được tạo sau lệnh này**, và chỉ áp dụng cho object do **đúng role được khai báo trong `FOR ROLE`** tạo ra — không hồi tố, không áp dụng chéo qua role khác tạo bảng.

---

## 7. Nguyên tắc Least Privilege — thiết kế phân quyền thực tế

Mô hình phổ biến trong hệ thống production, thường có 3 tầng role:

```
admin_role       → full quyền, dùng cho DBA/migration
app_service_role → CRUD giới hạn trên bảng nghiệp vụ, KHÔNG có DROP/TRUNCATE/ALTER
readonly_role    → chỉ SELECT, dùng cho BI/reporting/analytics
```

Nguyên tắc:
- Ứng dụng (Spring Boot) **không nên** kết nối bằng role có quyền `SUPERUSER` hay `CREATEDB`.
- Role dùng cho report/BI chỉ nên có `SELECT`, tuyệt đối không có `INSERT/UPDATE/DELETE`.
- Migration tool (Flyway/Liquibase) có thể cần role riêng có quyền `CREATE`/`ALTER` trên schema, tách biệt với role runtime của ứng dụng.

---

## 8. Column-level Privilege (phân quyền cấp cột)

PostgreSQL cho phép cấp quyền chi tiết đến từng cột, không chỉ toàn bảng — hữu ích khi 1 bảng có cột nhạy cảm (vd lương, số CMND) mà không muốn tách bảng riêng.

```sql
GRANT SELECT (id, full_name, email) ON customers TO support_staff;
-- support_staff KHÔNG thấy được cột "national_id" hay "salary" nếu có
```

Khi cấp quyền cấp cột cho `UPDATE`, cũng chỉ định rõ cột nào được sửa:

```sql
GRANT UPDATE (status) ON orders TO support_staff;
-- support_staff chỉ được sửa cột status, không sửa được các cột khác
```

---

## 9. `REVOKE` — thu hồi quyền

```sql
REVOKE quyền [, ...] ON object FROM role_name;
```

Lưu ý về **thứ tự và tính cộng dồn của quyền**: PostgreSQL không có khái niệm "deny" tường minh như SQL Server — quyền chỉ có 2 trạng thái *có* hoặc *không có*. Nếu 1 role có quyền qua nhiều đường (trực tiếp + qua membership group), `REVOKE` trực tiếp trên role đó **không** thu hồi được quyền role đang có gián tiếp qua group — phải revoke đúng ở nguồn cấp quyền, hoặc revoke membership (`REVOKE group_role FROM role`).

```sql
REVOKE readonly_group FROM alice;  -- gỡ membership, alice mất toàn bộ quyền thừa hưởng từ group
```

---

## 10. Row Level Security (RLS) — mở rộng của Permission ở mức dòng dữ liệu

Permission thông thường (`GRANT`) kiểm soát **object nào được thao tác** (bảng nào, cột nào). RLS kiểm soát sâu hơn: **dòng nào trong object đó được nhìn thấy/sửa**.

```sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON orders
FOR SELECT
USING (tenant_id = current_setting('app.current_tenant')::int);
```

- `USING`: điều kiện lọc dòng khi đọc/update/delete.
- `WITH CHECK`: điều kiện khi ghi (insert/update) — dữ liệu ghi vào phải thỏa điều kiện.
- Table owner và superuser **mặc định bypass RLS**, trừ khi bật `FORCE ROW LEVEL SECURITY`.
- Vai trò `BYPASSRLS` (thuộc tính role) cũng bỏ qua toàn bộ RLS bất kể policy nào.

RLS thường dùng cho mô hình **multi-tenant** (nhiều khách hàng dùng chung 1 bảng, chỉ thấy dữ liệu của mình) — bổ sung an toàn ở tầng DB ngay cả khi tầng application có lỗi quên filter `WHERE tenant_id = ...`.

---

## 11. `SECURITY DEFINER` vs `SECURITY INVOKER` — liên hệ chặt với Role/Permission

- **`SECURITY INVOKER`** (mặc định): function/procedure chạy với quyền của **người gọi** — người gọi cần có đủ quyền trên mọi object bên trong function.
- **`SECURITY DEFINER`**: chạy với quyền của **người tạo (owner)** — cho phép user thường gọi function để "mượn" quyền cao hơn một cách có kiểm soát, mà không cần cấp trực tiếp quyền đó lên bảng gốc.

```sql
CREATE FUNCTION get_revenue_report() RETURNS TABLE(...) 
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp
AS $$ ... $$;

GRANT EXECUTE ON FUNCTION get_revenue_report() TO analyst;
-- analyst không cần SELECT trực tiếp lên bảng orders/order_items
```

⚠️ Luôn đi kèm `SET search_path` cố định khi dùng `SECURITY DEFINER`, tránh rủi ro bị chèn schema độc hại (privilege escalation).

---

## 12. Introspection — kiểm tra quyền hiện tại của hệ thống

```sql
-- Xem danh sách role và thuộc tính
\du

-- Xem quyền trên 1 bảng cụ thể
\dp table_name

-- Query catalog trực tiếp
SELECT grantee, privilege_type
FROM information_schema.role_table_grants
WHERE table_name = 'orders';

-- Kiểm tra 1 role có quyền cụ thể hay không
SELECT has_table_privilege('alice', 'orders', 'SELECT');

-- Xem role nào là thành viên của role nào
SELECT r.rolname AS member, g.rolname AS member_of
FROM pg_auth_members m
JOIN pg_roles r ON m.member = r.oid
JOIN pg_roles g ON m.roleid = g.oid;
```

---

## 13. Bảng tổng hợp các lệnh cốt lõi

| Lệnh | Mục đích |
|---|---|
| `CREATE ROLE / USER` | Tạo role mới |
| `ALTER ROLE` | Đổi thuộc tính role (password, LOGIN, CONNECTION LIMIT...) |
| `DROP ROLE` | Xóa role (phải revoke/reassign hết quyền và object trước) |
| `GRANT ... TO role` | Cấp quyền trên object, hoặc cấp membership |
| `REVOKE ... FROM role` | Thu hồi quyền hoặc membership |
| `ALTER DEFAULT PRIVILEGES` | Định nghĩa quyền mặc định cho object tạo trong tương lai |
| `SET ROLE` / `RESET ROLE` | Tạm chuyển sang quyền của role khác trong session |
| `REASSIGN OWNED BY` | Chuyển toàn bộ object thuộc sở hữu 1 role sang role khác (dùng trước khi DROP role) |
| `DROP OWNED BY` | Xóa toàn bộ object + quyền thuộc về 1 role (dùng trước khi DROP role) |

---

## 14. Lỗi thường gặp khi làm việc với Role/Permission

| Lỗi | Nguyên nhân thường gặp |
|---|---|
| `permission denied for schema public` | Quên `GRANT USAGE ON SCHEMA` |
| `permission denied for sequence xxx_id_seq` | Cấp quyền `INSERT` trên table có cột `serial`/`identity` nhưng quên cấp `USAGE`/`SELECT` trên sequence liên quan |
| Bảng mới tạo user không thấy dù đã GRANT trước đó | Thiếu `ALTER DEFAULT PRIVILEGES`, phải GRANT thủ công cho từng bảng mới |
| `role "xxx" cannot be dropped because some objects depend on it` | Role vẫn còn sở hữu object hoặc còn quyền — cần `REASSIGN OWNED BY` / `DROP OWNED BY` trước |
| RLS bật nhưng owner vẫn thấy hết dữ liệu | Quên `FORCE ROW LEVEL SECURITY`, hoặc role có `BYPASSRLS` |
| Cấp `SELECT` nhưng vẫn không xem được cột nhạy cảm | Đang dùng column-level privilege đúng như thiết kế — không phải lỗi |

---

## 15. Tóm tắt tư duy cốt lõi khi thiết kế phân quyền

1. Luôn đi từ **Database → Schema → Object** khi cấp quyền, không bỏ sót tầng nào.
2. Dùng **role nhóm (group role, NOLOGIN)** để gom quyền, gán user vào group thay vì cấp quyền lẻ tẻ cho từng user.
3. Dùng `ALTER DEFAULT PRIVILEGES` ngay từ đầu dự án để tránh phải nhớ grant tay cho từng bảng mới.
4. Áp dụng **least privilege**: tách role theo mục đích (app, report, admin, migration).
5. Với dữ liệu nhạy cảm ở mức dòng, dùng **RLS**; ở mức cột, dùng **column-level privilege**.
6. Với nhu cầu "mượn quyền cao hơn có kiểm soát", dùng **`SECURITY DEFINER`** kèm `search_path` cố định thay vì cấp quyền cao trực tiếp cho user.
