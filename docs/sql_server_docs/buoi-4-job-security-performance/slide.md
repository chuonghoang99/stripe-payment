---
marp: true
theme: default
paginate: true
size: 16:9
---

# Buổi 4/4: SQL Server Agent Job, Security & Performance Tuning

### Buổi cuối khóa đào tạo SQL Server (180 phút)

**Mục tiêu buổi học:**

- Hiểu kiến trúc SQL Server Agent và tự tạo được Job tự động hóa (qua SSMS UI và qua T-SQL)
- Biết cách xem Job History để debug khi job chạy lỗi
- Nắm khái niệm cơ bản về Security: Login/User, Role, GRANT/DENY/REVOKE
- Nắm khái niệm cơ bản về Performance Tuning: đọc Execution Plan, tránh lỗi viết query phổ biến
- Hoàn thành bài Capstone tổng hợp toàn bộ kiến thức 4 buổi (View + Procedure + Job)

---

## Agenda buổi học (180 phút)

| # | Nội dung | Thời lượng |
|---|----------|-----------|
| 1 | SQL Server Agent & Job **(trọng tâm)** | 70 phút |
| 2 | Security *(giới thiệu sơ lược)* | 25 phút |
| 3 | Performance Tuning *(giới thiệu sơ lược)* | 25 phút |
| 4 | Tổng kết khóa học + Bài tập Capstone | 60 phút |

Toàn bộ ví dụ dùng chung CSDL **CompanyDB** đã dùng xuyên suốt Buổi 1-3.

---

# Phần 1: SQL Server Agent & Job

## (70 phút — phần trọng tâm của buổi)

Tự động hóa các tác vụ định kỳ trong SQL Server: backup, dọn dẹp dữ liệu, chạy report, ETL đơn giản...

---

## SQL Server Agent là gì?

- Là một **Windows Service** chạy nền, cài kèm SQL Server (`SQL Server Agent`)
- Chức năng: lên lịch và thực thi tự động các tác vụ (Job) mà không cần con người can thiệp
- Lưu toàn bộ cấu hình Job/Schedule/Log trong CSDL hệ thống **msdb**
- Cần được **Start** (running) thì Job mới chạy được — kiểm tra trong SSMS: SQL Server Agent phải có biểu tượng "mũi tên xanh"
- Nếu Agent service dừng → toàn bộ Job đã lên lịch sẽ **không** chạy

---

## Các khái niệm cốt lõi

- **Job**: một tác vụ tự động, gồm 1 hoặc nhiều bước (Step)
- **Job Step**: một bước thực thi cụ thể (chạy script, chạy lệnh OS...); các step chạy tuần tự, có thể định nghĩa "nếu step lỗi thì làm gì" (quit job, chuyển step khác...)
- **Schedule**: lịch chạy (một lần, lặp lại theo ngày/tuần/tháng, hoặc khi SQL Server Agent start)
- **Operator**: đối tượng (thường là email) nhận thông báo khi Job thành công/thất bại
- **Alert**: cảnh báo được kích hoạt bởi 1 sự kiện (lỗi SQL Server, hoặc job fail) → gửi tới Operator

---

## Các loại Job Step (Subsystem)

Mỗi Step chọn 1 "loại" (subsystem) thực thi:

- **Transact-SQL script (TSQL)** — phổ biến nhất, chạy T-SQL trực tiếp
- **Operating system (CmdExec)** — chạy lệnh command line / batch file
- **PowerShell** — chạy script PowerShell
- **SQL Server Integration Services (SSIS)** — chạy gói SSIS
- **Replication**, **Analysis Services** — dùng cho các tác vụ chuyên biệt

→ Buổi này tập trung vào loại **T-SQL Script**, phổ biến nhất với dev.

---

## Tạo Job qua SSMS UI (các bước)

1. Object Explorer → **SQL Server Agent** → chuột phải **Jobs** → **New Job...**
2. Tab **General**: đặt tên Job, mô tả, Owner
3. Tab **Steps** → **New...**: đặt tên step, chọn **Type = Transact-SQL script (T-SQL)**, chọn Database, gõ câu lệnh (thường là `EXEC` 1 stored procedure)
4. Tab **Schedules** → **New...**: chọn tần suất (Daily/Weekly/...), giờ chạy
5. Tab **Notifications**: chọn Operator nhận email khi Job Complete/Fail (tùy chọn)
6. **OK** để lưu → Job xuất hiện dưới **Jobs**, chuột phải → **Start Job at Step...** để chạy thử ngay

---

## Tạo Job qua T-SQL — Ví dụ thực tế

**Bài toán**: mỗi ngày dọn dẹp các đơn hàng `Status = 'Cancelled'` cũ hơn 90 ngày trong CompanyDB.

Bước 1 — tạo stored procedure dọn dẹp (đã học cách viết Procedure ở Buổi 3):

```sql
USE CompanyDB;
GO
CREATE OR ALTER PROCEDURE dbo.sp_CleanupCancelledOrders
AS
BEGIN
    SET NOCOUNT ON;

    DELETE od
    FROM OrderDetails od
    INNER JOIN Orders o ON o.OrderID = od.OrderID
    WHERE o.Status = N'Cancelled'
      AND o.OrderDate < DATEADD(DAY, -90, CAST(GETDATE() AS DATE));

    DELETE FROM Orders
    WHERE Status = N'Cancelled'
      AND OrderDate < DATEADD(DAY, -90, CAST(GETDATE() AS DATE));
END
GO
```

---

## Tạo Job qua T-SQL — `sp_add_job`

Bước 2 — tạo Job trong `msdb`:

```sql
USE msdb;
GO

EXEC dbo.sp_add_job
    @job_name = N'Job_Cleanup_CancelledOrders',
    @enabled = 1,
    @description = N'Xoa don hang Cancelled qua 90 ngay trong CompanyDB',
    @category_name = N'[Uncategorized (Local)]';
```

- `sp_add_job` chỉ tạo "vỏ" Job — chưa có Step, chưa có Schedule, chưa gán server
- Job vừa tạo chưa chạy được, cần thêm Step + Schedule + gán vào job server (bước tiếp theo)

---

## Tạo Job qua T-SQL — `sp_add_jobstep`

```sql
EXEC dbo.sp_add_jobstep
    @job_name = N'Job_Cleanup_CancelledOrders',
    @step_name = N'Step_ExecCleanupProc',
    @step_id = 1,
    @subsystem = N'TSQL',
    @database_name = N'CompanyDB',
    @command = N'EXEC dbo.sp_CleanupCancelledOrders;',
    @on_success_action = 1,   -- 1 = Quit the job reporting success
    @on_fail_action = 2;      -- 2 = Quit the job reporting failure
```

- `@subsystem = 'TSQL'` → step chạy T-SQL script
- `@command` chính là câu lệnh sẽ được thực thi (ở đây gọi thẳng stored procedure)
- Một Job có thể có nhiều Step (`@step_id` tăng dần), chạy tuần tự

---

## Tạo Job qua T-SQL — `sp_add_schedule` & gán lịch

```sql
EXEC dbo.sp_add_schedule
    @schedule_name = N'Schedule_Daily_02AM',
    @freq_type = 4,          -- 4 = Daily
    @freq_interval = 1,      -- mỗi 1 ngày
    @active_start_time = 020000; -- 02:00:00 sang

EXEC dbo.sp_attach_schedule
    @job_name = N'Job_Cleanup_CancelledOrders',
    @schedule_name = N'Schedule_Daily_02AM';
```

- `sp_add_schedule` tạo lịch (độc lập, có thể tái sử dụng cho nhiều Job)
- `sp_attach_schedule` gắn lịch đó vào 1 Job cụ thể
- `@freq_type = 4` (Daily) là giá trị hay dùng nhất; còn có Weekly (8), Monthly (16)...

---

## Tạo Job qua T-SQL — `sp_add_jobserver`

```sql
EXEC dbo.sp_add_jobserver
    @job_name = N'Job_Cleanup_CancelledOrders',
    @server_name = N'(local)';
```

- Bước **bắt buộc cuối cùng**: gán Job vào 1 SQL Server instance (job server) thì Job mới thật sự được Agent thực thi
- Với instance đơn (không cluster), dùng `@server_name = '(local)'`
- Sau bước này, Job đã sẵn sàng chạy đúng lịch — có thể kiểm tra lại trong SSMS: **SQL Server Agent > Jobs**

---

## Chạy thử & theo dõi Job

```sql
-- Chay thu Job ngay lap tuc (khong doi den lich)
EXEC msdb.dbo.sp_start_job @job_name = N'Job_Cleanup_CancelledOrders';

-- Xem trang thai lan chay gan nhat
SELECT job.name, activity.run_requested_date, activity.stop_execution_date
FROM msdb.dbo.sysjobactivity activity
JOIN msdb.dbo.sysjobs job ON job.job_id = activity.job_id
WHERE job.name = N'Job_Cleanup_CancelledOrders';
```

- `sp_start_job` dùng để test job ngay, không cần chờ schedule
- Có thể theo dõi tiến trình qua SSMS: chuột phải Job → **View History**

---

## Xem Job History để debug khi Job lỗi

Qua SSMS UI:
- Chuột phải Job → **View History** → xem từng lần chạy (Run Date, Duration, Status, Message lỗi chi tiết)

Qua T-SQL:

```sql
EXEC msdb.dbo.sp_help_jobhistory
    @job_name = N'Job_Cleanup_CancelledOrders';
```

- Cột `run_status`: 0 = Failed, 1 = Succeeded, 2 = Retry, 3 = Canceled
- `message` chứa nội dung lỗi T-SQL gốc (số lỗi, dòng lỗi...) — rất hữu ích khi debug
- Nguyên nhân lỗi thường gặp: sai tên database/procedure, thiếu quyền của **SQL Server Agent Service Account**, lock/deadlock khi chạy trùng giờ cao điểm

---

## Alert & Notification cơ bản (khái niệm)

- **Database Mail**: cơ chế gửi email từ trong SQL Server (dùng SMTP), phải được cấu hình/enable trước (`sp_configure 'Database Mail XPs'`) — buổi này chỉ giới thiệu khái niệm, không cấu hình chi tiết
- **Operator**: đại diện người/nhóm nhận thông báo (gắn với 1 địa chỉ email)
- **Notification**: cấu hình trong tab *Notifications* của Job — chọn gửi email khi job **Succeeds / Fails / Completes**
- **Alert**: có thể tạo alert riêng theo mã lỗi SQL Server (severity, error number) độc lập với Job, ví dụ báo khi có lỗi severity ≥ 16
- Thực tế vận hành: luôn bật notification "on failure" cho các Job quan trọng để phát hiện sự cố sớm

---

# Phần 2: Security

## ⚠️ Phần giới thiệu — không đi sâu (25 phút)

Mục tiêu: nắm khái niệm nền tảng để hiểu cách SQL Server kiểm soát quyền truy cập, chưa đi vào cấu hình bảo mật nâng cao (encryption, row-level security, auditing...).

---

## Authentication: Windows vs SQL Server

- **Windows Authentication**: dùng tài khoản Windows/Active Directory đang đăng nhập để kết nối SQL Server — không cần nhập thêm mật khẩu, được khuyến nghị vì bảo mật cao hơn (quản lý tập trung qua AD)
- **SQL Server Authentication**: tạo tài khoản riêng (username/password) lưu trong SQL Server, độc lập với hệ điều hành — cần thiết khi ứng dụng không nằm trong domain, hoặc kết nối từ ngoài mạng nội bộ
- Server có thể cấu hình **Mixed Mode** (cho phép cả 2 kiểu) hoặc chỉ **Windows Authentication Mode**

---

## Login vs User — 2 cấp độ quyền

| | **Login** | **User** |
|---|---|---|
| Phạm vi | Cấp **Server** (toàn instance) | Cấp **Database** (từng CSDL) |
| Vai trò | Định danh để "đăng nhập" vào SQL Server | Định danh để truy cập tài nguyên trong 1 database cụ thể |
| Quan hệ | 1 Login có thể map tới User ở nhiều database khác nhau | 1 User luôn thuộc về đúng 1 database |

```sql
-- Tao Login o cap Server
CREATE LOGIN sales_readonly WITH PASSWORD = 'Str0ngP@ss!2026';

-- Tao User trong CompanyDB, map voi Login vua tao
USE CompanyDB;
CREATE USER sales_readonly FOR LOGIN sales_readonly;
```

---

## Server Role vs Database Role

- **Server Role**: nhóm quyền cấp server, vd `sysadmin` (toàn quyền), `serveradmin`, `securityadmin`... → hiếm khi gán cho user ứng dụng thông thường
- **Database Role**: nhóm quyền cấp database, các role dựng sẵn hay dùng:
  - `db_datareader` — chỉ được **SELECT** mọi bảng trong database
  - `db_datawriter` — được **INSERT/UPDATE/DELETE** mọi bảng
  - `db_owner` — toàn quyền trên database (tương đương chủ sở hữu)

```sql
ALTER ROLE db_datareader ADD MEMBER sales_readonly;
```

- Có thể tạo **Custom Role** riêng nếu quyền dựng sẵn quá rộng/hẹp so với nhu cầu

---

## GRANT / DENY / REVOKE trên object cụ thể

```sql
-- Cap quyen SELECT tren 1 bang cu the
GRANT SELECT ON dbo.Orders TO sales_readonly;

-- Cap quyen thuc thi 1 stored procedure cu the
GRANT EXECUTE ON dbo.sp_CleanupCancelledOrders TO sales_readonly;

-- Tu choi tuyet doi quyen DELETE (DENY luon thang GRANT, ke ca gan qua Role)
DENY DELETE ON dbo.Orders TO sales_readonly;

-- Thu hoi quyen da cap truoc do (khong con GRANT, cung khong con DENY)
REVOKE SELECT ON dbo.Orders FROM sales_readonly;
```

- `GRANT` = cấp quyền, `DENY` = cấm tuyệt đối (ưu tiên cao nhất, ghi đè mọi GRANT), `REVOKE` = xóa bỏ 1 quyền đã cấp/cấm trước đó
- Có thể GRANT/DENY trên: bảng, view, cột cụ thể, stored procedure, function...

---

## Nguyên tắc Least Privilege

- Chỉ cấp **đúng và đủ** quyền cần thiết cho từng tài khoản/ứng dụng — không cấp `db_owner` hay `sysadmin` "cho tiện"
- Ưu tiên gán quyền qua **Role** (dễ quản lý, dễ audit) hơn là gán lẻ tẻ cho từng User
- Tài khoản ứng dụng (connection string) nên tách riêng theo tác vụ: tài khoản chỉ đọc report, tài khoản ghi dữ liệu nghiệp vụ, tài khoản chạy Job...
- Định kỳ rà soát lại quyền đã cấp — gỡ bỏ quyền của tài khoản không còn dùng
- Đây là nền tảng để tránh rủi ro bảo mật/thao tác nhầm trên production

---

# Phần 3: Performance Tuning

## ⚠️ Phần giới thiệu — không đi sâu (25 phút)

Mục tiêu: biết cách quan sát 1 query đang chạy như thế nào và nhận diện vài lỗi viết query phổ biến, chưa đi vào tuning index/query chuyên sâu.

---

## Execution Plan là gì?

- Là "kế hoạch thực thi" mà SQL Server Optimizer chọn để chạy 1 câu query (bảng nào đọc trước, dùng index nào, join theo thuật toán nào...)
- **Estimated Execution Plan**: kế hoạch dự đoán, dựa trên Statistics — SQL Server **không cần chạy thật** câu query để lấy được
- **Actual Execution Plan**: kế hoạch thật cộng thêm số liệu thực tế sau khi query **đã chạy xong** (số dòng thực tế, số lần thực thi...)
- Actual Plan đáng tin hơn Estimated Plan khi Statistics bị lỗi thời hoặc số liệu ước tính sai lệch nhiều

---

## Cách xem Execution Plan trong SSMS

- **Estimated Plan**: `Ctrl + L` (hoặc menu Query → Display Estimated Execution Plan) — không cần chạy query
- **Actual Plan**: `Ctrl + M` (hoặc menu Query → Include Actual Execution Plan) **trước khi** bấm Execute — plan hiện ra ở tab riêng sau khi query chạy xong
- Rê chuột vào từng node trong plan để xem chi tiết: số dòng ước tính/thực tế, chi phí (Cost), số lần thực thi...

```sql
-- Vi du: bat Ctrl+M roi chay query nay de xem Actual Plan
SELECT o.OrderID, c.CustomerName, o.OrderDate, o.Status
FROM Orders o
JOIN Customers c ON c.CustomerID = o.CustomerID
WHERE o.Status = N'Completed';
```

---

## Đọc hiểu vài ký hiệu cơ bản

- **Table Scan**: đọc toàn bộ bảng (không dùng index) → thường là dấu hiệu cần thêm index nếu bảng lớn
- **Index Scan**: đọc toàn bộ 1 index (đỡ hơn Table Scan nhưng vẫn đọc hết)
- **Index Seek**: dùng index để nhảy thẳng đến đúng dòng cần — hiệu quả nhất, mục tiêu cần hướng tới
- Mỗi node trong plan hiển thị **% cost** so với tổng chi phí toàn plan → nên tập trung tối ưu node có % cost cao nhất trước
- Mũi tên (arrow) nối giữa các node càng **dày** → càng nhiều dòng dữ liệu được truyền qua bước đó

---

## Missing Index & Statistics

- Khi 1 query có thể chạy nhanh hơn nếu có thêm index, SSMS hiển thị gợi ý màu xanh lá **"Missing Index"** ngay phía trên execution plan, kèm sẵn câu lệnh `CREATE INDEX` đề xuất
- Gợi ý này chỉ mang tính tham khảo — cần cân nhắc thêm (index tốn chi phí ghi, dung lượng) trước khi áp dụng, không nên tạo index theo mọi gợi ý

```sql
-- Vi du cau lenh SSMS co the goi y (minh hoa)
CREATE INDEX IX_Orders_Status_OrderDate
    ON dbo.Orders (Status, OrderDate);
```

- **Statistics**: là thông tin thống kê phân bố dữ liệu trên cột/index, giúp Optimizer ước lượng số dòng → chọn plan phù hợp
- Statistics lỗi thời (dữ liệu thay đổi nhiều nhưng chưa cập nhật) là nguyên nhân phổ biến khiến Optimizer chọn sai plan; SQL Server tự động cập nhật, hoặc chạy thủ công `UPDATE STATISTICS dbo.Orders;`

---

## Best Practice viết query hiệu quả

**1. Tránh `SELECT *`** — chỉ lấy đúng cột cần dùng (giảm I/O, tận dụng covering index)

```sql
-- Nen tranh
SELECT * FROM Orders WHERE Status = N'Completed';
-- Nen dung
SELECT OrderID, CustomerID, OrderDate FROM Orders WHERE Status = N'Completed';
```

**2. Tránh bọc hàm lên cột trong `WHERE`** — làm mất khả năng dùng Index Seek (SARGable)

```sql
-- Xau: ham YEAR() boc cot -> Table/Index Scan
SELECT * FROM Orders WHERE YEAR(OrderDate) = 2025;
-- Tot: so sanh truc tiep cot -> co the Index Seek
SELECT * FROM Orders WHERE OrderDate >= '2025-01-01' AND OrderDate < '2026-01-01';
```

**3. Tránh N+1 query** — thay vì query lặp trong vòng lặp ứng dụng cho từng đơn hàng, gộp lại thành 1 câu JOIN duy nhất lấy hết dữ liệu cần

---

# Phần 4: Tổng kết khóa học & Bài tập Capstone

## (60 phút)

Vận dụng toàn bộ kiến thức 4 buổi vào 1 bài toán thực tế hoàn chỉnh.

---

## Đề bài Capstone: Module Báo cáo doanh thu tự động

Xây dựng module tự động tính và lưu báo cáo doanh thu theo tháng/nhân viên:

- **(a) View** `vw_MonthlyRevenueByEmployee`: tổng hợp doanh thu (từ `Orders` + `OrderDetails`, chỉ tính đơn `Status = 'Completed'`) theo từng tháng và từng nhân viên
- **(b) Stored Procedure** `sp_GenerateMonthlyRevenueReport`: tính doanh thu tháng (mặc định tháng hiện tại) từ View trên, rồi lưu (log) kết quả vào bảng `RevenueReportLog`
- **(c) SQL Server Agent Job**: gọi Procedure trên **tự động mỗi ngày lúc 00:00** (nửa đêm), dùng đầy đủ `sp_add_job` / `sp_add_jobstep` / `sp_add_schedule` / `sp_attach_schedule` / `sp_add_jobserver`

→ Chi tiết đề bài đầy đủ + đáp án nằm trong file **lab.md**.

---

## Recap Buổi 1 — Nền tảng Table & Index

- Kiểu dữ liệu SQL Server, thiết kế bảng, Primary/Foreign Key, Constraint
- Khái niệm Index cơ bản (Clustered/Non-Clustered) và vì sao index giúp truy vấn nhanh hơn

## Recap Buổi 2 — Query nâng cao & View

- JOIN nhiều bảng, Subquery, CTE, Window Function (`ROW_NUMBER`, `RANK`...)
- Tạo và sử dụng View để đóng gói logic truy vấn phức tạp, tái sử dụng nhiều nơi

---

## Recap Buổi 3 — Procedure, Function & Trigger

- Viết Stored Procedure có tham số đầu vào/đầu ra, xử lý logic nghiệp vụ trong CSDL
- Viết Function (Scalar/Table-Valued) để tái sử dụng logic tính toán trong query
- Giới thiệu sơ lược Trigger — tự động phản ứng khi dữ liệu thay đổi

## Recap Buổi 4 — Job, Security & Performance (hôm nay)

- Tự động hóa tác vụ định kỳ bằng SQL Server Agent Job
- Khái niệm nền tảng về phân quyền và cách quan sát hiệu năng query

---

## Lời cảm ơn & Hướng học tiếp theo

Cảm ơn cả nhóm đã tham gia đầy đủ và tích cực suốt 4 buổi đào tạo! Đây mới là nền tảng — SQL Server còn rất nhiều điều để khám phá sâu hơn:

- **Performance Tuning chuyên sâu**: đọc kỹ Execution Plan, chiến lược Indexing nâng cao, Query Store, xử lý deadlock/blocking
- **High Availability**: Always On Availability Groups, Failover Clustering
- **Replication**: đồng bộ dữ liệu giữa nhiều SQL Server instance
- **SSIS (SQL Server Integration Services)**: xây dựng pipeline ETL phức tạp hơn Agent Job thuần T-SQL
- **Security nâng cao**: Row-Level Security, Data Encryption, Auditing

**Chúc các bạn áp dụng tốt kiến thức vào công việc thực tế!**
