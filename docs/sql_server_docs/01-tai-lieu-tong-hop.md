# Tài Liệu Tổng Hợp — Đào Tạo SQL Server Cho Developer

Tài liệu này gom toàn bộ kiến thức của 4 buổi đào tạo vào một chỗ để tra cứu nhanh,
kèm so sánh với **PostgreSQL** ở những điểm dev hay nhầm lẫn khi chuyển qua lại giữa
hai hệ quản trị CSDL. Ví dụ minh họa dùng chung database mẫu **CompanyDB**
([00-du-lieu-mau/setup.sql](00-du-lieu-mau/setup.sql)).

> Tham khảo kỹ thuật: [sqlservertutorial.net](https://www.sqlservertutorial.net/) (mục
> *SQL Server Basics*, *Views*, *Stored Procedures*, *User-defined Functions*, *Triggers*,
> *Indexes*) và [Microsoft Learn – T-SQL reference](https://learn.microsoft.com/sql/t-sql/).

## Mục lục

1. [Kiến trúc SQL Server & SSMS](#1-kiến-trúc-sql-server--ssms)
2. [Kiểu dữ liệu (Data Types)](#2-kiểu-dữ-liệu-data-types)
3. [Table & Constraint](#3-table--constraint)
4. [Index](#4-index)
5. [Query nâng cao](#5-query-nâng-cao)
6. [View](#6-view)
7. [Stored Procedure](#7-stored-procedure)
8. [Function](#8-function)
9. [Trigger (giới thiệu)](#9-trigger-giới-thiệu)
10. [SQL Server Agent Job](#10-sql-server-agent-job)
11. [Security (giới thiệu)](#11-security-giới-thiệu)
12. [Performance Tuning (giới thiệu)](#12-performance-tuning-giới-thiệu)
13. [So sánh SQL Server vs PostgreSQL](#13-so-sánh-sql-server-vs-postgresql)
14. [Tài liệu tham khảo](#14-tài-liệu-tham-khảo)

---

## 1. Kiến trúc SQL Server & SSMS

- SQL Server hoạt động theo mô hình **Client – Server**: Database Engine (server) xử lý
  và lưu trữ dữ liệu; SSMS/ứng dụng (client) gửi câu lệnh T-SQL qua connection string.
- **Edition**: Express (miễn phí, giới hạn dung lượng), Developer (miễn phí, đầy đủ tính
  năng, không dùng production), Standard, Enterprise (có phí).
- **SSMS**: Object Explorer (cây Database/Table/View/Procedure...), New Query (soạn/chạy
  T-SQL), Connect bằng Windows Authentication hoặc SQL Server Authentication.
- **Database → Schema → Object**: 1 server chứa nhiều database; 1 database chia thành các
  schema (mặc định `dbo`) để nhóm object theo chức năng/quyền truy cập.

*(Xem chi tiết + demo: [buoi-1-nen-tang-table/slide.md](buoi-1-nen-tang-table/slide.md))*

---

## 2. Kiểu dữ liệu (Data Types)

| Nhóm | Kiểu | Ghi chú |
|---|---|---|
| Số nguyên chính xác | `bit`, `tinyint`, `smallint`, `int`, `bigint` | `bit` chỉ chứa 0/1/NULL |
| Số thập phân chính xác | `decimal(p,s)` / `numeric(p,s)` | Đồng nghĩa với nhau, dùng cho tiền tệ cần chính xác tuyệt đối |
| Tiền tệ | `money`, `smallmoney` | Có sẵn định dạng tiền tệ, nhưng cộng đồng khuyến nghị dùng `decimal` để tránh sai số làm tròn khi tính toán phức tạp |
| Số thực gần đúng | `float(n)`, `real` | Dùng cho tính toán khoa học, không dùng cho tiền tệ |
| Ngày giờ | `date`, `time`, `datetime2`, `datetimeoffset` (khuyến nghị dùng), `datetime`, `smalldatetime` (kiểu cũ) | `datetime2`/`datetimeoffset` chính xác hơn và chuẩn ANSI hơn `datetime` |
| Chuỗi không-Unicode | `char(n)`, `varchar(n)`, `varchar(max)` | 1 byte/ký tự, không lưu được tiếng Việt có dấu đúng cách |
| Chuỗi Unicode | `nchar(n)`, `nvarchar(n)`, `nvarchar(max)` | 2 byte/ký tự — **luôn dùng cho dữ liệu tiếng Việt** |
| Nhị phân | `binary(n)`, `varbinary(n)`, `varbinary(max)` | Lưu file/ảnh nhỏ, hash |
| Khác | `uniqueidentifier` (GUID), `xml`, `sql_variant`, `rowversion`, `table`, `cursor`, `hierarchyid` | `table` dùng cho biến bảng, TVF |

> `text`, `ntext`, `image` đã **deprecated** — dùng `varchar(max)`, `nvarchar(max)`,
> `varbinary(max)` thay thế (nguồn: sqlservertutorial.net – SQL Server Data Types).

**IDENTITY** — cột tự tăng, cú pháp `IDENTITY(seed, increment)`, mặc định `(1,1)`. Mỗi
bảng chỉ có **1** cột IDENTITY, thường là cột PK.

```sql
CREATE TABLE dbo.Sample (
    SampleID INT IDENTITY(1,1) PRIMARY KEY,
    Note NVARCHAR(200) NOT NULL
);
```

---

## 3. Table & Constraint

```sql
CREATE TABLE Employees (
    EmployeeID   INT IDENTITY(1,1) PRIMARY KEY,
    FullName     NVARCHAR(150) NOT NULL,
    Email        NVARCHAR(150) NOT NULL UNIQUE,
    DepartmentID INT NOT NULL
        CONSTRAINT FK_Employees_Departments REFERENCES Departments(DepartmentID),
    Salary       DECIMAL(12,2) NOT NULL
        CONSTRAINT CK_Employees_Salary CHECK (Salary > 0),
    HireDate     DATE NOT NULL DEFAULT (GETDATE())
);
```

| Constraint | Vai trò |
|---|---|
| `PRIMARY KEY` | Định danh duy nhất mỗi dòng, mặc định tạo **Clustered Index** |
| `FOREIGN KEY` | Đảm bảo toàn vẹn tham chiếu giữa 2 bảng |
| `UNIQUE` | Giá trị không trùng lặp nhưng cho phép NULL (nhiều NULL vẫn hợp lệ) |
| `CHECK` | Ràng buộc điều kiện logic trên giá trị cột |
| `DEFAULT` | Giá trị mặc định khi INSERT không chỉ định cột đó |
| `NOT NULL` | Bắt buộc phải có giá trị |

**ALTER / DROP:**

```sql
ALTER TABLE Employees ADD Phone VARCHAR(20) NULL;
ALTER TABLE Employees ALTER COLUMN Phone VARCHAR(30) NULL;
ALTER TABLE Employees DROP COLUMN Phone;
DROP TABLE Employees;              -- phải xóa bảng con (FK) trước bảng cha
```

*(Chi tiết + lab: [buoi-1-nen-tang-table/](buoi-1-nen-tang-table/))*

---

## 4. Index

- **Clustered Index**: quyết định thứ tự vật lý lưu trữ dữ liệu trên đĩa — mỗi bảng chỉ
  có **1** clustered index (thường gắn với PRIMARY KEY).
- **Non-clustered Index**: cấu trúc B-Tree riêng trỏ về dòng dữ liệu gốc — 1 bảng có thể
  có **nhiều** non-clustered index.
- Đánh index giúp `SELECT`/`JOIN`/`WHERE` nhanh hơn nhưng làm chậm `INSERT`/`UPDATE`/`DELETE`
  (vì phải cập nhật cả index) — chỉ đánh index trên cột hay dùng để lọc/join/sắp xếp.

```sql
CREATE NONCLUSTERED INDEX IX_Orders_CustomerID ON Orders(CustomerID);
CREATE UNIQUE INDEX UX_Products_ProductName ON Products(ProductName);
```

*(Phần tuning chuyên sâu hơn — đọc execution plan, missing index — xem [mục 12](#12-performance-tuning-giới-thiệu).)*

---

## 5. Query nâng cao

### JOIN

| Loại | Kết quả |
|---|---|
| `INNER JOIN` | Chỉ lấy các dòng khớp ở cả 2 bảng |
| `LEFT JOIN` | Toàn bộ bảng trái + dòng khớp bên phải (NULL nếu không khớp) |
| `RIGHT JOIN` | Ngược lại LEFT JOIN |
| `FULL JOIN` | Hợp cả 2 chiều LEFT + RIGHT |
| `CROSS JOIN` | Tích Descartes — mọi kết hợp có thể |
| `SELF JOIN` | Bảng tự join với chính nó (vd: nhân viên — quản lý) |

```sql
SELECT e.FullName AS Employee, m.FullName AS Manager
FROM Employees e
LEFT JOIN Employees m ON e.ManagerID = m.EmployeeID;
```

### Subquery & CTE

```sql
-- CTE giúp query phức tạp dễ đọc hơn, có thể tham chiếu nhiều lần trong cùng câu lệnh
WITH RevenueByCustomer AS (
    SELECT o.CustomerID, SUM(od.Quantity * od.UnitPrice) AS Revenue
    FROM Orders o
    JOIN OrderDetails od ON od.OrderID = o.OrderID
    GROUP BY o.CustomerID
)
SELECT c.CustomerName, r.Revenue
FROM RevenueByCustomer r
JOIN Customers c ON c.CustomerID = r.CustomerID
ORDER BY r.Revenue DESC;
```

### Aggregate & GROUP BY/HAVING

`SUM`/`COUNT`/`AVG`/`MIN`/`MAX` đi cùng `GROUP BY`; `HAVING` lọc **sau** khi nhóm (khác
`WHERE` lọc **trước** khi nhóm).

### Window Function

```sql
SELECT EmployeeID, DepartmentID, Salary,
       RANK() OVER (PARTITION BY DepartmentID ORDER BY Salary DESC) AS RankInDept,
       SUM(Salary) OVER (PARTITION BY DepartmentID) AS DeptTotalSalary
FROM Employees;
```

`ROW_NUMBER()` luôn duy nhất; `RANK()` bỏ số khi có đồng hạng; `DENSE_RANK()` không bỏ số.

*(Chi tiết + lab: [buoi-2-query-view/](buoi-2-query-view/))*

---

## 6. View

```sql
CREATE VIEW vw_OrderSummary AS
SELECT o.OrderID, c.CustomerName, o.OrderDate,
       SUM(od.Quantity * od.UnitPrice) AS TotalAmount
FROM Orders o
JOIN Customers c ON c.CustomerID = o.CustomerID
JOIN OrderDetails od ON od.OrderID = o.OrderID
GROUP BY o.OrderID, c.CustomerName, o.OrderDate;
```

- View là **câu SELECT được đặt tên**, không lưu dữ liệu riêng (trừ Indexed View).
- Dùng để: đơn giản hóa query phức tạp, ẩn cột nhạy cảm (bảo mật), tạo lớp tương thích khi
  đổi schema.
- **Updatable View**: chỉ update được nếu view dựa trên 1 bảng và không có `GROUP BY`/`DISTINCT`/hàm tổng hợp.
- `WITH CHECK OPTION`: chặn INSERT/UPDATE qua view nếu dữ liệu không còn thỏa điều kiện `WHERE` của view.
- **Indexed View** (`WITH SCHEMABINDING` + `CREATE UNIQUE CLUSTERED INDEX`): vật chất hóa
  dữ liệu view ra đĩa, tăng tốc đọc nhưng tốn thêm chi phí ghi — chỉ dùng khi thật sự cần.

*(Chi tiết + lab: [buoi-2-query-view/](buoi-2-query-view/))*

---

## 7. Stored Procedure

```sql
CREATE PROCEDURE dbo.usp_CancelOrder
    @OrderID INT
AS
BEGIN
    SET NOCOUNT ON;
    BEGIN TRY
        BEGIN TRAN;
            UPDATE Products SET StockQty = StockQty + od.Quantity
            FROM Products p JOIN OrderDetails od ON od.ProductID = p.ProductID
            WHERE od.OrderID = @OrderID;

            UPDATE Orders SET Status = 'Cancelled' WHERE OrderID = @OrderID;
        COMMIT TRAN;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRAN;
        THROW;
    END CATCH
END;
```

- Tham số **IN** (mặc định), **OUTPUT** (trả giá trị ra ngoài), có thể có **giá trị mặc định**.
- `RETURN` chỉ trả về **1 số nguyên** (mã trạng thái), không dùng để trả dữ liệu nghiệp vụ.
- `TRY/CATCH` + `THROW`/`RAISERROR` để xử lý lỗi; `BEGIN TRAN/COMMIT/ROLLBACK` để đảm bảo
  nhiều thao tác ghi cùng thành công hoặc cùng thất bại (tính nguyên tử).
- Lợi ích: bảo mật (cấp quyền EXEC mà không cần cấp quyền trực tiếp lên bảng), tái sử dụng,
  execution plan được cache sẵn, giảm số round-trip giữa app và database.

*(Chi tiết + lab: [buoi-3-procedure-function/](buoi-3-procedure-function/))*

---

## 8. Function

| | Scalar Function | Table-Valued Function (TVF) |
|---|---|---|
| Trả về | 1 giá trị đơn | 1 tập kết quả (bảng) |
| Dùng trong SELECT/JOIN | Có | Có (như 1 bảng) |
| Inline vs Multi-statement | — | Inline: 1 câu RETURN SELECT (tối ưu hơn); Multi-statement: nhiều câu lệnh, trả qua biến TABLE |

```sql
CREATE FUNCTION dbo.ufn_GetOrderTotalAmount (@OrderID INT)
RETURNS MONEY
AS
BEGIN
    DECLARE @Total MONEY;
    SELECT @Total = SUM(Quantity * UnitPrice) FROM OrderDetails WHERE OrderID = @OrderID;
    RETURN ISNULL(@Total, 0);
END;
```

**Function vs Stored Procedure:** Function không được có side-effect (không INSERT/UPDATE
bảng thật), không dùng transaction/TRY-CATCH đầy đủ, nhưng gọi được trực tiếp trong
`SELECT`/`WHERE`/`JOIN` — điều Procedure không làm được.

*(Chi tiết + lab: [buoi-3-procedure-function/](buoi-3-procedure-function/))*

---

## 9. Trigger (giới thiệu)

> Phần này chỉ giới thiệu khái niệm, không đi sâu — không có bài lab bắt buộc riêng.

- **AFTER trigger**: chạy sau khi INSERT/UPDATE/DELETE đã xảy ra (dùng phổ biến nhất).
- **INSTEAD OF trigger**: chạy **thay cho** thao tác gốc — thường dùng trên View để làm
  view "updatable" theo logic tùy biến.
- Bảng ảo `inserted`/`deleted` chứa dữ liệu trước/sau thao tác.

```sql
CREATE TRIGGER trg_OrderDetails_ReduceStock
ON OrderDetails
AFTER INSERT
AS
BEGIN
    UPDATE p SET p.StockQty = p.StockQty - i.Quantity
    FROM Products p JOIN inserted i ON i.ProductID = p.ProductID;
END;
```

⚠️ Lạm dụng trigger gây khó debug (logic "ẩn", không thấy khi đọc câu lệnh gọi), có thể
ảnh hưởng performance nếu trigger phức tạp — cân nhắc kỹ trước khi dùng.

*(Chi tiết: [buoi-3-procedure-function/slide.md](buoi-3-procedure-function/slide.md))*

---

## 10. SQL Server Agent Job

- **SQL Server Agent**: dịch vụ Windows chạy nền, thực thi các **Job** theo lịch (**Schedule**).
- 1 Job gồm nhiều **Job Step** (mỗi step chạy 1 loại lệnh: T-SQL, PowerShell, SSIS...),
  chạy tuần tự, có thể cấu hình "on success/on failure đi đến step nào".
- Tạo Job bằng T-SQL qua các stored procedure hệ thống trong `msdb`:

```sql
EXEC msdb.dbo.sp_add_job @job_name = N'Dọn đơn hàng Cancelled';

EXEC msdb.dbo.sp_add_jobstep
    @job_name = N'Dọn đơn hàng Cancelled',
    @step_name = N'Chạy procedure dọn dẹp',
    @subsystem = N'TSQL',
    @command = N'EXEC dbo.usp_PurgeCancelledOrders;',
    @database_name = N'CompanyDB';

EXEC msdb.dbo.sp_add_schedule
    @schedule_name = N'Hàng ngày lúc 00:00',
    @freq_type = 4, @freq_interval = 1,
    @active_start_time = 000000;

EXEC msdb.dbo.sp_attach_schedule
    @job_name = N'Dọn đơn hàng Cancelled', @schedule_name = N'Hàng ngày lúc 00:00';

EXEC msdb.dbo.sp_add_jobserver
    @job_name = N'Dọn đơn hàng Cancelled';
```

- Xem lịch sử chạy job (thành công/thất bại, thông báo lỗi) qua **Job History** trong SSMS
  hoặc bảng `msdb.dbo.sysjobhistory`.
- **Database Mail** + **Operator**: cấu hình để Agent gửi email khi job fail (chỉ giới
  thiệu khái niệm ở khóa này).

*(Chi tiết + lab: [buoi-4-job-security-performance/](buoi-4-job-security-performance/))*

---

## 11. Security (giới thiệu)

> Phần này chỉ giới thiệu khái niệm, không đi sâu.

| Khái niệm | Ý nghĩa |
|---|---|
| **Login** | Tài khoản ở cấp **server** (Windows Authentication hoặc SQL Server Authentication) |
| **User** | Ánh xạ của 1 Login vào **1 database** cụ thể |
| **Server Role** | Nhóm quyền cấp server (vd `sysadmin`) |
| **Database Role** | Nhóm quyền cấp database (vd `db_datareader`, `db_datawriter`, `db_owner`) |

```sql
CREATE LOGIN reporting_user WITH PASSWORD = 'Str0ng!Passw0rd';
CREATE USER reporting_user FOR LOGIN reporting_user;
GRANT SELECT ON vw_OrderSummary TO reporting_user;
DENY SELECT ON Employees(Salary) TO reporting_user;
```

Nguyên tắc **least privilege**: chỉ cấp đúng quyền cần thiết (vd: tài khoản báo cáo chỉ
nên có quyền `SELECT` trên view, không cần quyền trên bảng gốc).

*(Chi tiết + lab: [buoi-4-job-security-performance/](buoi-4-job-security-performance/))*

---

## 12. Performance Tuning (giới thiệu)

> Phần này chỉ giới thiệu khái niệm, không đi sâu.

- **Execution Plan**: sơ đồ SQL Server chọn để thực thi query — xem bằng
  `Ctrl+M` (Include Actual Execution Plan) trong SSMS.
- **Table Scan** (đọc toàn bộ bảng) thường chậm hơn **Index Seek** (tìm trực tiếp qua index)
  — nhìn % cost của từng bước để biết chỗ nào đang chậm nhất.
- SSMS có thể gợi ý **Missing Index** ngay trên execution plan.
- **Statistics**: SQL Server dựa vào thống kê phân bố dữ liệu để chọn kế hoạch thực thi —
  statistics lỗi thời có thể khiến optimizer chọn plan kém tối ưu.
- Best practice nhanh: tránh `SELECT *`, tránh bọc hàm lên cột trong `WHERE`
  (vd `WHERE YEAR(OrderDate) = 2025` làm mất khả năng dùng index trên `OrderDate`; nên viết
  `WHERE OrderDate >= '2025-01-01' AND OrderDate < '2026-01-01'`), tránh N+1 query (query
  lặp trong vòng lặp ứng dụng thay vì gộp thành 1 câu JOIN).

*(Chi tiết + lab: [buoi-4-job-security-performance/](buoi-4-job-security-performance/))*

---

## 13. So sánh SQL Server vs PostgreSQL

Bảng dưới giúp dev đã quen PostgreSQL (hoặc ngược lại) tránh nhầm cú pháp/khái niệm khi
chuyển đổi giữa 2 hệ quản trị.

| Chủ đề | SQL Server (T-SQL) | PostgreSQL |
|---|---|---|
| Cột tự tăng | `IDENTITY(1,1)` | `SERIAL` / `GENERATED ALWAYS AS IDENTITY` (chuẩn SQL hơn, khuyến nghị) |
| Giới hạn số dòng | `SELECT TOP (10) ...` | `SELECT ... LIMIT 10` (cả 2 đều hỗ trợ `OFFSET ... FETCH NEXT ... ROWS ONLY` theo chuẩn ANSI) |
| Nối chuỗi | `+` (vd `FirstName + ' ' + LastName`) | `\|\|` (vd `first_name \|\| ' ' \|\| last_name`) |
| Xử lý NULL | `ISNULL(expr, default)` (2 tham số, ép kiểu theo tham số đầu) | `COALESCE(expr, default)` (chuẩn ANSI, nhiều tham số) — **cả 2 hệ đều hỗ trợ `COALESCE`**, chỉ SQL Server mới có thêm `ISNULL` |
| Kiểu chuỗi Unicode | Phải dùng `NVARCHAR`/`NCHAR` riêng cho Unicode, `VARCHAR` mặc định không phải Unicode | `VARCHAR`/`TEXT` mặc định đã là UTF-8 (Unicode), không cần kiểu `N` riêng |
| Ngày giờ hiện tại | `GETDATE()`, `SYSDATETIME()` | `NOW()`, `CURRENT_TIMESTAMP` |
| Cộng/trừ ngày | `DATEADD(day, 7, OrderDate)` | `OrderDate + INTERVAL '7 day'` |
| Hàm khác biệt ngày | `DATEDIFF(day, d1, d2)` | `d2 - d1` (trả về kiểu `interval`), hoặc `date_part` |
| Upsert (insert hoặc update) | `MERGE` statement (cú pháp dài, có từ SQL Server 2008) | `INSERT ... ON CONFLICT (...) DO UPDATE ...` (gọn hơn); PostgreSQL 15+ cũng có `MERGE` |
| Thủ tục/hàm | Stored Procedure (`CREATE PROCEDURE`) và Function tách biệt rõ ràng, ngôn ngữ **T-SQL** | Trước PG 11 chỉ có Function; từ PG 11 có `CREATE PROCEDURE`, viết bằng **PL/pgSQL** (hoặc SQL, PL/Python...) |
| Xử lý lỗi | `BEGIN TRY ... END TRY BEGIN CATCH ... END CATCH`, `THROW`/`RAISERROR` | `BEGIN ... EXCEPTION WHEN ... THEN ... END;` trong khối PL/pgSQL |
| Biến | `DECLARE @Bien INT;` (tiền tố `@`) | `bien INT;` khai báo trong khối `DECLARE` của PL/pgSQL (không tiền tố `@`) |
| Trigger | Thân trigger viết trực tiếp trong `CREATE TRIGGER`, có bảng ảo `inserted`/`deleted` | Phải viết 1 **trigger function** riêng (PL/pgSQL, dùng `NEW`/`OLD`) rồi `CREATE TRIGGER ... EXECUTE FUNCTION ...` gắn vào |
| Tự động hóa/lập lịch | **SQL Server Agent** (Job/Step/Schedule) tích hợp sẵn | Không có sẵn — cần cài thêm extension **`pg_cron`** hoặc dùng công cụ ngoài (cron hệ điều hành, Airflow...) |
| Đăng nhập/phân quyền | Tách biệt **Login** (server) và **User** (database) | Gộp chung thành khái niệm **Role** (vừa là login vừa là user/group quyền) |
| Xem định nghĩa view/proc | `sp_helptext 'ten_object'` hoặc `OBJECT_DEFINITION()` | `\d+ ten_view` (psql) hoặc `pg_get_viewdef('ten_view'::regclass)` |
| Catalog / metadata | `sys.*` views (`sys.tables`, `sys.columns`...) | `pg_catalog.*` hoặc chuẩn ANSI `information_schema.*` (cả 2 hệ đều hỗ trợ `information_schema`) |
| Kiểu JSON | `NVARCHAR` + hàm `JSON_VALUE`/`JSON_QUERY`/`OPENJSON` (JSON lưu dạng text, không có kiểu native trước SQL Server 2025) | Có kiểu native `JSON`/`JSONB` (JSONB lưu dạng nhị phân, lập chỉ mục được) |
| Full-text/tìm kiếm nâng cao | Full-Text Search (cần bật riêng) | Hỗ trợ `tsvector`/`tsquery` native, mạnh hơn cho tìm kiếm text |

**3 điểm dễ gây lỗi nhất khi chuyển đổi qua lại:**

1. **Nối chuỗi `+` vs `||`** — dùng nhầm sẽ báo lỗi cú pháp ngay (không phải lỗi ngầm), dễ phát hiện.
2. **`ISNULL` vs `COALESCE`** — `ISNULL(a, b)` trả về kiểu dữ liệu của `a`, còn `COALESCE`
   trả về kiểu ưu tiên cao nhất trong danh sách tham số theo chuẩn ANSI — có thể gây khác
   biệt kiểu dữ liệu ngầm (implicit conversion) nếu không để ý.
3. **Không có SQL Server Agent trên PostgreSQL** — khi port hệ thống dùng nhiều Job từ
   SQL Server sang PostgreSQL, cần thiết kế lại phần lập lịch bằng `pg_cron` hoặc scheduler
   bên ngoài, đây thường là phần bị bỏ sót khi ước lượng effort migrate.

---

## 14. Tài liệu tham khảo

- [sqlservertutorial.net](https://www.sqlservertutorial.net/) — tutorial T-SQL chi tiết theo
  từng chủ đề (Basics, Views, Stored Procedures, User-defined Functions, Triggers, Indexes...).
- [Microsoft Learn – Transact-SQL Reference](https://learn.microsoft.com/sql/t-sql/language-reference)
  — tài liệu chính thức, đầy đủ nhất cho mọi cú pháp T-SQL.
- [Microsoft Learn – SQL Server Agent](https://learn.microsoft.com/sql/ssms/agent/sql-server-agent)
  — tài liệu chính thức về Job/Schedule/Alert.
- [PostgreSQL Official Documentation](https://www.postgresql.org/docs/current/) — đối chiếu
  khi cần tra cứu chi tiết cú pháp PostgreSQL trong bảng so sánh ở mục 13.
