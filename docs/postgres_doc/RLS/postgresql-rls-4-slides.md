# Slide 1 --- PostgreSQL Row-Level Security (RLS)

## RLS = kiểm soát quyền truy cập ở mức từng ROW

``` text
GRANT  → User được access TABLE nào?
RLS    → User được access ROW nào trong table?
```

### Ví dụ: SaaS Multi-Tenant

``` text
orders
┌────┬───────────┬────────┐
│ id │ tenant_id │ amount │
├────┼───────────┼────────┤
│ 1  │ A         │ 100    │ ← Tenant A thấy
│ 2  │ A         │ 200    │ ← Tenant A thấy
│ 3  │ B         │ 500    │ ✕
│ 4  │ C         │ 700    │ ✕
└────┴───────────┴────────┘
```

Bật RLS:

``` sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
```

**Lợi ích chính:** Database tự enforce quyền truy cập theo từng row,
giảm rủi ro truy cập nhầm dữ liệu khi query thiếu điều kiện lọc.

------------------------------------------------------------------------

# Slide 2 --- RLS Policy: `USING` & `WITH CHECK`

``` sql
CREATE POLICY tenant_isolation
ON orders
FOR ALL

USING (
    tenant_id =
    current_setting('app.tenant_id', true)::UUID
)

WITH CHECK (
    tenant_id =
    current_setting('app.tenant_id', true)::UUID
);
```

## Hai khái niệm cần nhớ

``` text
USING
  │
  └─ Row hiện tại nào được ACCESS?
       SELECT / UPDATE / DELETE


WITH CHECK
  │
  └─ Row mới có hợp lệ không?
       INSERT / UPDATE
```

### Ví dụ với Tenant A

``` text
SELECT → chỉ thấy A

UPDATE B → ✕ không target được

INSERT tenant=A → ✓

INSERT tenant=B → ✕ WITH CHECK reject

UPDATE A → tenant=B → ✕ WITH CHECK reject
```

------------------------------------------------------------------------

# Slide 3 --- How PostgreSQL RLS Works

Khi RLS được bật:

``` sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
```

PostgreSQL sẽ áp dụng policy tự động cho query.

User chạy:

``` sql
SELECT *
FROM orders
WHERE status = 'PENDING';
```

Policy:

``` sql
CREATE POLICY tenant_policy
ON orders
USING (
    tenant_id =
    current_setting('app.tenant_id', true)::UUID
);
```

Có thể hình dung PostgreSQL xử lý:

``` text
User Query
WHERE status = 'PENDING'
        │
        ↓
   PostgreSQL RLS
        │
        + Policy condition
        │
        ↓
status = 'PENDING'
AND
tenant_id = current tenant
        │
        ↓
   Query Planner
        │
        ↓
Only permitted rows
```

## Default Deny

Nếu RLS đã bật nhưng không có policy phù hợp:

``` text
ENABLE RLS
    +
No Policy
    ↓
Default Deny
    ↓
Không row nào được access
```

## Multiple Policies

Một table có thể có nhiều policy:

``` sql
CREATE POLICY employee_policy ...
CREATE POLICY manager_policy ...
```

Mặc định, các **permissive policies** được kết hợp theo logic `OR`:

``` text
Policy A
   OR
Policy B
   OR
Policy C
```

PostgreSQL cũng hỗ trợ **restrictive policies**, dùng để bổ sung các
điều kiện hạn chế theo logic `AND`.

------------------------------------------------------------------------

# Slide 4 --- RLS: Important Behaviors & Limitations

## 1. Một số role có thể bypass RLS

Thông thường:

``` text
Superuser
   │
   ├── bypass
   ↓
BYPASSRLS role
   │
   ├── bypass
   ↓
Table Owner
   │
   └── thường bypass
```

Có thể buộc table owner chịu RLS:

``` sql
ALTER TABLE orders
FORCE ROW LEVEL SECURITY;
```

## 2. RLS không thay thế `GRANT`

``` sql
GRANT SELECT, INSERT, UPDATE
ON orders
TO app_user;
```

Mental model:

``` text
             PostgreSQL Security

                    Query
                      │
                      ↓
                   GRANT
                      │
             Can access table?
                      │
                  YES ↓
                     RLS
                      │
             Can access row?
                      │
                  YES ↓
                    Data
```

## 3. RLS ảnh hưởng Query Planning & Performance

Policy đơn giản:

``` sql
USING (
    tenant_id = current_setting('app.tenant_id', true)::UUID
);
```

nên có index phù hợp:

``` sql
CREATE INDEX idx_orders_tenant
ON orders(tenant_id);
```

Policy phức tạp:

``` sql
USING (
    EXISTS (
        SELECT 1
        FROM permissions p
        WHERE p.user_id = ...
          AND p.resource_id = orders.id
    )
);
```

có thể làm tăng query cost.

Kiểm tra execution plan bằng:

``` sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT ...;
```

## Mental Model Tổng Kết

``` text
                  PostgreSQL RLS

                       │
              ENABLE ROW SECURITY
                       │
                       ↓
                    POLICY
                  /          \
              USING       WITH CHECK
                │              │
         Existing rows      New rows
                │              │
                └──────┬───────┘
                       ↓
                Query Planner
                       ↓
              Permitted Rows Only
```
