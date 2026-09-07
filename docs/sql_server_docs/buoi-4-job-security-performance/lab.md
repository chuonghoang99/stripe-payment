# Lab Buổi 4: SQL Server Agent Job, Security & Performance Tuning

**Mục tiêu**: Thực hành tạo SQL Server Agent Job bằng T-SQL, thực hành các thao tác phân quyền cơ bản (Login/User/GRANT), và làm quen với việc đọc Execution Plan trong SSMS. Bài cuối (Capstone) yêu cầu kết hợp View + Stored Procedure + Job để xây dựng module báo cáo doanh thu tự động — tổng hợp toàn bộ kiến thức 4 buổi.

Toàn bộ bài tập dùng chung CSDL **CompanyDB** (đã tạo sẵn ở buổi 1, xem `00-du-lieu-mau/setup.sql`).

---

## ĐỀ BÀI

### Nhóm A — SQL Server Agent & Job

**Bài 1.** Viết script T-SQL tạo một Job tên `Job_HelloAgent` gồm 1 step duy nhất, chạy T-SQL đơn giản là `PRINT 'Job dang chay!'`, được lên lịch chạy hàng ngày lúc **23:00**. Dùng đầy đủ `sp_add_job`, `sp_add_jobstep`, `sp_add_schedule`, `sp_attach_schedule`, `sp_add_jobserver`.

**Bài 2.** Viết stored procedure `dbo.sp_CleanupCancelledOrders` trong CompanyDB để xóa các dòng trong `OrderDetails` và `Orders` có `Status = 'Cancelled'` và `OrderDate` cũ hơn 90 ngày so với hiện tại (chú ý thứ tự xóa để không vi phạm Foreign Key).

**Bài 3.** Từ procedure ở Bài 2, tạo Job `Job_Cleanup_CancelledOrders` gồm 1 step gọi procedure đó, lên lịch chạy hàng ngày lúc **02:00 sáng**.

**Bài 4.** Sau khi chạy thử Job ở Bài 3 (dùng `sp_start_job`), viết câu lệnh T-SQL để xem lại lịch sử chạy (Job History) của job này, và cho biết cột nào trong kết quả giúp bạn biết job chạy thành công hay thất bại.

### Nhóm B — Security

**Bài 5.** Tạo 1 SQL Server Login tên `report_viewer` với mật khẩu bất kỳ đủ mạnh, sau đó tạo User tương ứng trong CompanyDB và thêm vào role `db_datareader`.

**Bài 6.** Với user `report_viewer` ở Bài 5: cấp quyền `SELECT` trên bảng `Customers` và `Orders`, nhưng **DENY** quyền `DELETE` trên bảng `Orders` (dù user chỉ ở role đọc, hãy viết tường minh để hiểu cơ chế DENY). Sau đó viết câu lệnh thu hồi (REVOKE) quyền SELECT trên bảng `Customers` đã cấp.

### Nhóm C — Performance Tuning

**Bài 7.** Cho câu query sau, hãy bật **Actual Execution Plan** (Ctrl+M) trong SSMS rồi chạy thử, quan sát plan và trả lời: (a) SQL Server dùng Table Scan hay Index Seek trên bảng `Orders`? (b) SSMS có gợi ý Missing Index không, nội dung gợi ý là gì? (c) Hãy viết lại query cho tốt hơn về mặt SARGable (không bọc hàm lên cột trong WHERE).

```sql
SELECT *
FROM Orders
WHERE YEAR(OrderDate) = 2025 AND MONTH(OrderDate) = 6;
```

### Nhóm D — Bài tập Capstone (tổng hợp toàn khóa)

**Bài 8.** Xây dựng module **"Báo cáo doanh thu tự động"** cho CompanyDB, gồm đầy đủ 3 phần sau:

- **(a) View** `dbo.vw_MonthlyRevenueByEmployee`: tổng hợp doanh thu theo **tháng** và theo **nhân viên** (`EmployeeID`, `FullName`), chỉ tính các đơn hàng có `Status = 'Completed'`. Doanh thu 1 đơn hàng = tổng của `Quantity * UnitPrice` trên các dòng `OrderDetails` thuộc đơn đó.
- **(b) Bảng log** `dbo.RevenueReportLog` để lưu lại kết quả mỗi lần chạy báo cáo (tháng báo cáo, nhân viên, doanh thu, thời điểm sinh báo cáo).
- **(c) Stored Procedure** `dbo.sp_GenerateMonthlyRevenueReport`: nhận tham số `@ReportMonth DATE = NULL` (nếu NULL thì mặc định lấy tháng hiện tại), lấy dữ liệu từ View ở mục (a) cho đúng tháng đó, rồi INSERT vào bảng log ở mục (b).
- **(d) SQL Server Agent Job** `Job_GenerateMonthlyRevenueReport`: gọi procedure ở mục (c) tự động **mỗi ngày lúc 00:00 (nửa đêm)**, dùng đầy đủ `sp_add_job`/`sp_add_jobstep`/`sp_add_schedule`/`sp_attach_schedule`/`sp_add_jobserver`.

Yêu cầu: viết đầy đủ script T-SQL có thể chạy tuần tự từ đầu đến cuối trên CompanyDB.

---

## ĐÁP ÁN

### Bài 1 — Job Hello Agent

```sql
USE msdb;
GO

EXEC dbo.sp_add_job
    @job_name = N'Job_HelloAgent',
    @enabled = 1,
    @description = N'Job vi du - chi in ra 1 dong thong bao';

EXEC dbo.sp_add_jobstep
    @job_name = N'Job_HelloAgent',
    @step_name = N'Step_PrintHello',
    @step_id = 1,
    @subsystem = N'TSQL',
    @command = N'PRINT ''Job dang chay!'';',
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC dbo.sp_add_schedule
    @schedule_name = N'Schedule_Daily_23PM',
    @freq_type = 4,              -- Daily
    @freq_interval = 1,
    @active_start_time = 230000; -- 23:00:00

EXEC dbo.sp_attach_schedule
    @job_name = N'Job_HelloAgent',
    @schedule_name = N'Schedule_Daily_23PM';

EXEC dbo.sp_add_jobserver
    @job_name = N'Job_HelloAgent',
    @server_name = N'(local)';
```

**Giải thích**: `sp_add_job` chỉ tạo khung Job; phải có đủ `sp_add_jobstep` (nội dung chạy), `sp_add_schedule` + `sp_attach_schedule` (lịch chạy), và `sp_add_jobserver` (gán vào instance) thì Job mới hoạt động thật sự. Thiếu bước `sp_add_jobserver` là lỗi rất hay gặp khiến job "tạo xong mà không bao giờ tự chạy".

---

### Bài 2 — Procedure dọn dẹp đơn hàng Cancelled

```sql
USE CompanyDB;
GO

CREATE OR ALTER PROCEDURE dbo.sp_CleanupCancelledOrders
AS
BEGIN
    SET NOCOUNT ON;

    -- Xoa OrderDetails truoc de khong vi pham Foreign Key voi Orders
    DELETE od
    FROM OrderDetails od
    INNER JOIN Orders o ON o.OrderID = od.OrderID
    WHERE o.Status = N'Cancelled'
      AND o.OrderDate < DATEADD(DAY, -90, CAST(GETDATE() AS DATE));

    -- Sau do moi xoa Orders
    DELETE FROM Orders
    WHERE Status = N'Cancelled'
      AND OrderDate < DATEADD(DAY, -90, CAST(GETDATE() AS DATE));
END
GO
```

**Giải thích**: `OrderDetails` có Foreign Key trỏ về `Orders`, nên phải xóa các dòng con (`OrderDetails`) trước, rồi mới xóa dòng cha (`Orders`) — nếu làm ngược lại sẽ bị lỗi vi phạm ràng buộc khóa ngoại.

---

### Bài 3 — Job gọi procedure dọn dẹp lúc 2 giờ sáng

```sql
USE msdb;
GO

EXEC dbo.sp_add_job
    @job_name = N'Job_Cleanup_CancelledOrders',
    @enabled = 1,
    @description = N'Xoa don hang Cancelled qua 90 ngay trong CompanyDB';

EXEC dbo.sp_add_jobstep
    @job_name = N'Job_Cleanup_CancelledOrders',
    @step_name = N'Step_ExecCleanupProc',
    @step_id = 1,
    @subsystem = N'TSQL',
    @database_name = N'CompanyDB',
    @command = N'EXEC dbo.sp_CleanupCancelledOrders;',
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC dbo.sp_add_schedule
    @schedule_name = N'Schedule_Daily_02AM',
    @freq_type = 4,
    @freq_interval = 1,
    @active_start_time = 020000; -- 02:00:00 sang

EXEC dbo.sp_attach_schedule
    @job_name = N'Job_Cleanup_CancelledOrders',
    @schedule_name = N'Schedule_Daily_02AM';

EXEC dbo.sp_add_jobserver
    @job_name = N'Job_Cleanup_CancelledOrders',
    @server_name = N'(local)';

-- Chay thu ngay de kiem tra (khong can doi den 2h sang)
EXEC msdb.dbo.sp_start_job @job_name = N'Job_Cleanup_CancelledOrders';
```

**Giải thích**: `@database_name = 'CompanyDB'` trong `sp_add_jobstep` chỉ định step chạy trong context của CompanyDB, nên trong `@command` có thể gọi thẳng `EXEC dbo.sp_CleanupCancelledOrders;` mà không cần viết `USE CompanyDB;` trước.

---

### Bài 4 — Xem Job History để debug

```sql
EXEC msdb.dbo.sp_help_jobhistory
    @job_name = N'Job_Cleanup_CancelledOrders';
```

Hoặc truy vấn trực tiếp bảng hệ thống:

```sql
SELECT
    j.name AS JobName,
    h.step_name,
    h.run_date,
    h.run_time,
    h.run_status,   -- 0=Failed, 1=Succeeded, 2=Retry, 3=Canceled
    h.message
FROM msdb.dbo.sysjobhistory h
JOIN msdb.dbo.sysjobs j ON j.job_id = h.job_id
WHERE j.name = N'Job_Cleanup_CancelledOrders'
ORDER BY h.run_date DESC, h.run_time DESC;
```

**Giải thích**: Cột `run_status` cho biết kết quả lần chạy (1 = thành công, 0 = thất bại). Khi job lỗi, cột `message` chứa nội dung lỗi T-SQL gốc — đây là nơi đầu tiên cần xem để debug (ví dụ sai tên procedure, thiếu quyền, deadlock...).

---

### Bài 5 — Tạo Login/User và gán role đọc

```sql
-- Cap Server: tao Login
CREATE LOGIN report_viewer WITH PASSWORD = 'R3port!Viewer2026';

-- Cap Database: tao User map voi Login, roi gan vao role db_datareader
USE CompanyDB;
GO
CREATE USER report_viewer FOR LOGIN report_viewer;
ALTER ROLE db_datareader ADD MEMBER report_viewer;
```

**Giải thích**: `CREATE LOGIN` tạo định danh ở cấp Server (dùng để kết nối vào SQL Server instance); `CREATE USER ... FOR LOGIN` tạo định danh tương ứng ở cấp Database (dùng để cấp quyền truy cập tài nguyên bên trong CompanyDB). `db_datareader` là role dựng sẵn cho phép SELECT mọi bảng trong database.

---

### Bài 6 — GRANT / DENY / REVOKE cụ thể

```sql
USE CompanyDB;
GO

-- Cap quyen SELECT tren 2 bang cu the
GRANT SELECT ON dbo.Customers TO report_viewer;
GRANT SELECT ON dbo.Orders TO report_viewer;

-- Cam tuyet doi quyen DELETE tren Orders (DENY luon thang moi GRANT/Role)
DENY DELETE ON dbo.Orders TO report_viewer;

-- Thu hoi lai quyen SELECT tren Customers da cap truoc do
REVOKE SELECT ON dbo.Customers FROM report_viewer;
```

**Giải thích**: Dù `report_viewer` đã ở role `db_datareader` (được SELECT toàn bộ database), ví dụ này minh họa việc GRANT/DENY/REVOKE tường minh trên từng bảng để hiểu cơ chế — trong thực tế nên ưu tiên dùng Role cho gọn, chỉ GRANT/DENY lẻ khi cần ngoại lệ cụ thể. `DENY` luôn được ưu tiên cao nhất, kể cả khi user đã có quyền qua Role.

---

### Bài 7 — Đọc Execution Plan

Với query:

```sql
SELECT *
FROM Orders
WHERE YEAR(OrderDate) = 2025 AND MONTH(OrderDate) = 6;
```

**Nhận xét (kết quả điển hình khi bật Actual Execution Plan):**

- (a) SQL Server sẽ dùng **Table Scan** (hoặc Index Scan nếu có index trên `OrderDate`) — **không thể** dùng Index Seek, vì cột `OrderDate` bị bọc trong hàm `YEAR()`/`MONTH()` khiến Optimizer không so sánh trực tiếp được giá trị cột với hằng số (mất tính chất SARGable).
- (b) Vì quét toàn bộ bảng, nếu `Orders` có index phù hợp trên `OrderDate` mà không tận dụng được, SSMS thường gợi ý Missing Index dạng: `CREATE INDEX ... ON dbo.Orders (OrderDate)` (nội dung cụ thể tùy dữ liệu/thống kê thực tế).
- (c) Viết lại theo hướng SARGable — so sánh trực tiếp trên cột, không bọc hàm:

```sql
SELECT OrderID, CustomerID, EmployeeID, OrderDate, Status
FROM Orders
WHERE OrderDate >= '2025-06-01' AND OrderDate < '2025-07-01';
```

Query viết lại vừa tránh bọc hàm lên cột (cho phép Index Seek nếu có index trên `OrderDate`), vừa tránh `SELECT *` (chỉ lấy cột cần dùng).

---

### Bài 8 — Capstone: Module Báo cáo doanh thu tự động

**Bước (a) — Tạo View tổng hợp doanh thu theo tháng/nhân viên:**

```sql
USE CompanyDB;
GO

CREATE OR ALTER VIEW dbo.vw_MonthlyRevenueByEmployee
AS
SELECT
    e.EmployeeID,
    e.FullName,
    DATEFROMPARTS(YEAR(o.OrderDate), MONTH(o.OrderDate), 1) AS RevenueMonth,
    SUM(od.Quantity * od.UnitPrice) AS TotalRevenue
FROM Orders o
INNER JOIN OrderDetails od ON od.OrderID = o.OrderID
INNER JOIN Employees e ON e.EmployeeID = o.EmployeeID
WHERE o.Status = N'Completed'
GROUP BY e.EmployeeID, e.FullName,
         DATEFROMPARTS(YEAR(o.OrderDate), MONTH(o.OrderDate), 1);
GO
```

*Giải thích*: `DATEFROMPARTS(YEAR(...), MONTH(...), 1)` chuẩn hóa mỗi tháng về "ngày 1 của tháng đó" để dùng làm khóa nhóm (`RevenueMonth`), giúp so sánh/join theo tháng dễ dàng ở bước sau. Chỉ tính đơn `Completed` vì đơn `Pending`/`Cancelled` chưa/sẽ không phát sinh doanh thu thật.

**Bước (b) — Tạo bảng log lưu kết quả báo cáo:**

```sql
CREATE TABLE dbo.RevenueReportLog (
    LogID        INT IDENTITY(1,1) PRIMARY KEY,
    ReportMonth  DATE NOT NULL,
    EmployeeID   INT NOT NULL,
    TotalRevenue DECIMAL(18,2) NOT NULL,
    GeneratedAt  DATETIME NOT NULL DEFAULT GETDATE(),
    CONSTRAINT FK_RevenueReportLog_Employees
        FOREIGN KEY (EmployeeID) REFERENCES dbo.Employees(EmployeeID)
);
GO
```

*Giải thích*: Bảng log lưu lại lịch sử mỗi lần chạy báo cáo (không ghi đè), gồm tháng báo cáo, nhân viên, doanh thu tính được, và thời điểm sinh báo cáo (`GeneratedAt`) để phục vụ đối chiếu/audit sau này.

**Bước (c) — Stored Procedure tính và lưu báo cáo:**

```sql
CREATE OR ALTER PROCEDURE dbo.sp_GenerateMonthlyRevenueReport
    @ReportMonth DATE = NULL
AS
BEGIN
    SET NOCOUNT ON;

    -- Neu khong truyen thang, mac dinh lay thang hien tai
    IF @ReportMonth IS NULL
        SET @ReportMonth = DATEFROMPARTS(YEAR(GETDATE()), MONTH(GETDATE()), 1);
    ELSE
        SET @ReportMonth = DATEFROMPARTS(YEAR(@ReportMonth), MONTH(@ReportMonth), 1);

    INSERT INTO dbo.RevenueReportLog (ReportMonth, EmployeeID, TotalRevenue)
    SELECT RevenueMonth, EmployeeID, TotalRevenue
    FROM dbo.vw_MonthlyRevenueByEmployee
    WHERE RevenueMonth = @ReportMonth;
END
GO
```

*Giải thích*: Procedure nhận `@ReportMonth` tùy chọn — nếu không truyền (NULL) thì tự lấy tháng hiện tại, chuẩn hóa về ngày 1 của tháng rồi lọc dữ liệu từ View ở bước (a) để INSERT vào bảng log. Đây chính là logic sẽ được Job gọi lại mỗi ngày.

**Test thử procedure trước khi tạo Job:**

```sql
EXEC dbo.sp_GenerateMonthlyRevenueReport;              -- tinh cho thang hien tai
EXEC dbo.sp_GenerateMonthlyRevenueReport @ReportMonth = '2025-06-01'; -- tinh cho thang 6/2025
SELECT * FROM dbo.RevenueReportLog ORDER BY GeneratedAt DESC;
```

**Bước (d) — Tạo SQL Server Agent Job chạy lúc 00:00 mỗi ngày:**

```sql
USE msdb;
GO

EXEC dbo.sp_add_job
    @job_name = N'Job_GenerateMonthlyRevenueReport',
    @enabled = 1,
    @description = N'Tinh va luu bao cao doanh thu theo thang/nhan vien moi dem';

EXEC dbo.sp_add_jobstep
    @job_name = N'Job_GenerateMonthlyRevenueReport',
    @step_name = N'Step_ExecGenerateReportProc',
    @step_id = 1,
    @subsystem = N'TSQL',
    @database_name = N'CompanyDB',
    @command = N'EXEC dbo.sp_GenerateMonthlyRevenueReport;',
    @on_success_action = 1,
    @on_fail_action = 2;

EXEC dbo.sp_add_schedule
    @schedule_name = N'Schedule_Daily_Midnight',
    @freq_type = 4,               -- Daily
    @freq_interval = 1,
    @active_start_time = 000000; -- 00:00:00 (nua dem)

EXEC dbo.sp_attach_schedule
    @job_name = N'Job_GenerateMonthlyRevenueReport',
    @schedule_name = N'Schedule_Daily_Midnight';

EXEC dbo.sp_add_jobserver
    @job_name = N'Job_GenerateMonthlyRevenueReport',
    @server_name = N'(local)';

-- Chay thu ngay de kiem tra toan bo luong hoat dong dung
EXEC msdb.dbo.sp_start_job @job_name = N'Job_GenerateMonthlyRevenueReport';
```

**Kiểm tra kết quả sau khi Job chạy:**

```sql
SELECT * FROM CompanyDB.dbo.RevenueReportLog ORDER BY GeneratedAt DESC;

EXEC msdb.dbo.sp_help_jobhistory
    @job_name = N'Job_GenerateMonthlyRevenueReport';
```

**Tổng kết luồng hoạt động của module**: mỗi đêm lúc 00:00, SQL Server Agent tự động kích hoạt `Job_GenerateMonthlyRevenueReport` → Job gọi `sp_GenerateMonthlyRevenueReport` → procedure đọc dữ liệu tổng hợp từ `vw_MonthlyRevenueByEmployee` (dựa trên `Orders`/`OrderDetails`/`Employees`) → ghi kết quả vào `RevenueReportLog`. Đây chính là 1 ví dụ hoàn chỉnh kết hợp **View (Buổi 2) + Stored Procedure (Buổi 3) + Agent Job (Buổi 4)**.
