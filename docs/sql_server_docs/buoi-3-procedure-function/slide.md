---
marp: true
theme: default
paginate: true
---

# Buổi 3: Stored Procedure, Function & Trigger

### Lập trình trong SQL Server

**Mục tiêu buổi học:**

- Viết được Stored Procedure có tham số đầu vào, tham số OUTPUT, RETURN
- Xử lý lỗi và transaction an toàn trong Procedure (TRY/CATCH, ROLLBACK)
- Viết được Scalar Function và Table-Valued Function, hiểu khi nào dùng Function thay vì Procedure
- Hiểu khái niệm Trigger (AFTER/INSTEAD OF) ở mức giới thiệu, biết rủi ro khi lạm dụng
- Áp dụng được các khái niệm trên với CSDL CompanyDB

---

## Agenda (180 phút)

| # | Chủ đề | Thời lượng |
|---|--------|-----------|
| 1 | Stored Procedure cơ bản | 40 phút |
| 2 | Stored Procedure nâng cao (OUTPUT, TRY/CATCH, Transaction) | 30 phút |
| 3 | Function (Scalar & Table-Valued) | 50 phút |
| 4 | Trigger (giới thiệu sơ lược) | 30 phút |
| 5 | Tổng kết + Q&A | 10 phút |
| — | *Thực hành (lab) — tách riêng* | *30 phút* |

---

# Phần 1: Stored Procedure cơ bản

## Stored Procedure là gì?

- Một khối lệnh T-SQL được **đặt tên, lưu sẵn** trong CSDL
- Có thể nhận **tham số đầu vào**, thực thi logic, trả kết quả (result set, OUTPUT, RETURN)
- Gọi lại nhiều lần bằng `EXEC`/`EXECUTE` thay vì gửi lại toàn bộ câu lệnh SQL

---

## Lợi ích của Stored Procedure

- **Bảo mật**: cấp quyền EXEC cho user mà không cần cấp quyền trực tiếp trên bảng
- **Tái sử dụng**: logic nghiệp vụ dùng chung ở nhiều nơi (app, report, job...)
- **Giảm round-trip**: nhiều câu lệnh gộp thành 1 lần gọi từ client đến server
- **Execution plan biên dịch sẵn**: SQL Server cache plan, lần gọi sau nhanh hơn (không phải parse/compile lại)
- **Giảm SQL Injection**: khi dùng tham số đúng cách thay vì nối chuỗi SQL động

---

## Cú pháp CREATE PROCEDURE

```sql
CREATE PROCEDURE dbo.usp_GetOrdersByCustomer
    @CustomerID INT
AS
BEGIN
    SET NOCOUNT ON; -- tránh trả về thông báo "n rows affected"

    SELECT o.OrderID, o.OrderDate, o.Status, o.EmployeeID
    FROM Orders o
    WHERE o.CustomerID = @CustomerID
    ORDER BY o.OrderDate DESC;
END;
```

- `SET NOCOUNT ON` là thói quen tốt: giảm network traffic, tránh nhiễu ở client

---

## Gọi Stored Procedure

```sql
-- Cách 1: EXEC + truyền tham số theo thứ tự
EXEC dbo.usp_GetOrdersByCustomer 3;

-- Cách 2: EXECUTE + truyền tham số theo tên (khuyến khích)
EXECUTE dbo.usp_GetOrdersByCustomer @CustomerID = 3;
```

- Nên truyền tham số theo **tên** (`@CustomerID = 3`) để code rõ ràng, không phụ thuộc thứ tự khai báo
- Có thể sửa lại (`ALTER PROCEDURE`) hoặc xóa (`DROP PROCEDURE`) khi cần

---

## Nhiều tham số đầu vào

```sql
CREATE PROCEDURE dbo.usp_GetOrdersByCustomerAndStatus
    @CustomerID INT,
    @Status     NVARCHAR(20)
AS
BEGIN
    SET NOCOUNT ON;

    SELECT OrderID, OrderDate, Status
    FROM Orders
    WHERE CustomerID = @CustomerID
      AND Status = @Status;
END;
```

```sql
EXEC dbo.usp_GetOrdersByCustomerAndStatus
    @CustomerID = 3, @Status = 'Completed';
```

---

# Phần 2: Stored Procedure nâng cao

## Tham số OUTPUT

- Cho phép Procedure **trả giá trị ngược lại** cho nơi gọi (ngoài result set)
- Khai báo bằng từ khóa `OUTPUT` cả ở `CREATE PROCEDURE` lẫn khi `EXEC`

```sql
CREATE PROCEDURE dbo.usp_GetOrderTotal
    @OrderID INT,
    @Total   MONEY OUTPUT
AS
BEGIN
    SET NOCOUNT ON;

    SELECT @Total = SUM(Quantity * UnitPrice)
    FROM OrderDetails
    WHERE OrderID = @OrderID;
END;
```

```sql
DECLARE @T MONEY;
EXEC dbo.usp_GetOrderTotal @OrderID = 1, @Total = @T OUTPUT;
SELECT @T AS OrderTotal;
```

---

## Giá trị mặc định tham số & RETURN

```sql
CREATE PROCEDURE dbo.usp_CountOrdersByStatus
    @Status NVARCHAR(20) = 'Pending' -- giá trị mặc định
AS
BEGIN
    SET NOCOUNT ON;
    RETURN (SELECT COUNT(*) FROM Orders WHERE Status = @Status);
END;
```

```sql
DECLARE @Count INT;
EXEC @Count = dbo.usp_CountOrdersByStatus; -- dùng mặc định 'Pending'
SELECT @Count;
```

- `RETURN` chỉ trả về **1 số nguyên** (thường dùng làm mã lỗi/status), không dùng để trả dữ liệu nghiệp vụ

---

## Xử lý lỗi: TRY/CATCH + THROW/RAISERROR

```sql
BEGIN TRY
    SELECT 1 / 0; -- lỗi chia cho 0
END TRY
BEGIN CATCH
    SELECT
        ERROR_NUMBER()   AS ErrorNumber,
        ERROR_MESSAGE()  AS ErrorMessage,
        ERROR_LINE()     AS ErrorLine;

    THROW; -- ném lại lỗi gốc cho tầng gọi (khuyến nghị, từ SQL 2012+)
END CATCH;
```

- `THROW` (không tham số) giữ nguyên thông tin lỗi gốc — nên dùng thay cho `RAISERROR` trong code mới
- `RAISERROR('message', severity, state)` vẫn còn dùng khi cần custom message có định dạng

---

## Transaction trong Procedure

- `BEGIN TRAN` mở transaction, `COMMIT` xác nhận, `ROLLBACK` hủy toàn bộ thay đổi
- Kết hợp TRY/CATCH: lỗi xảy ra ở đâu → ROLLBACK toàn bộ, đảm bảo dữ liệu nhất quán (all-or-nothing)

```sql
BEGIN TRY
    BEGIN TRAN;
        UPDATE Products SET StockQty = StockQty - 5 WHERE ProductID = 1;
        -- ... các lệnh khác
    COMMIT TRAN;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRAN;
    THROW;
END CATCH;
```

---

## Ví dụ tổng hợp: Procedure tạo đơn hàng mới

```sql
CREATE PROCEDURE dbo.usp_CreateOrder
    @CustomerID INT,
    @EmployeeID INT,
    @ProductID  INT,
    @Quantity   INT,
    @NewOrderID INT OUTPUT
AS
BEGIN
    SET NOCOUNT ON;
    BEGIN TRY
        BEGIN TRAN;

        IF NOT EXISTS (SELECT 1 FROM Products
                       WHERE ProductID = @ProductID AND StockQty >= @Quantity)
            THROW 50001, 'Không đủ hàng trong kho.', 1;

        INSERT INTO Orders (CustomerID, EmployeeID, OrderDate, Status)
        VALUES (@CustomerID, @EmployeeID, GETDATE(), 'Pending');

        SET @NewOrderID = SCOPE_IDENTITY();
```

---

## Ví dụ tổng hợp (tiếp theo)

```sql
        INSERT INTO OrderDetails (OrderID, ProductID, Quantity, UnitPrice)
        SELECT @NewOrderID, @ProductID, @Quantity, UnitPrice
        FROM Products WHERE ProductID = @ProductID;

        UPDATE Products
        SET StockQty = StockQty - @Quantity
        WHERE ProductID = @ProductID;

        COMMIT TRAN;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0 ROLLBACK TRAN;
        THROW;
    END CATCH
END;
```

- `SCOPE_IDENTITY()`: lấy giá trị IDENTITY vừa insert **trong cùng scope** (an toàn hơn `@@IDENTITY`)
- Nếu bất kỳ bước nào lỗi → toàn bộ transaction bị hủy, kho hàng và đơn hàng không bị lệch nhau

---

# Phần 3: Function

## Scalar Function

- Nhận tham số đầu vào, **trả về đúng 1 giá trị** (số, chuỗi, ngày...)
- Có thể dùng trực tiếp trong `SELECT`, `WHERE`, `JOIN`

```sql
CREATE FUNCTION dbo.ufn_GetYearsOfService (@HireDate DATE)
RETURNS INT
AS
BEGIN
    RETURN DATEDIFF(YEAR, @HireDate, GETDATE());
END;
```

```sql
SELECT FullName, HireDate,
       dbo.ufn_GetYearsOfService(HireDate) AS YearsOfService
FROM Employees;
```

---

## Table-Valued Function: Inline TVF

- Trả về **1 bảng**, bên trong chỉ có **1 câu lệnh RETURN (SELECT)** duy nhất
- SQL Server tối ưu hóa như một view có tham số → hiệu năng tốt

```sql
CREATE FUNCTION dbo.ufn_OrdersByDateRange
    (@FromDate DATE, @ToDate DATE)
RETURNS TABLE
AS
RETURN
(
    SELECT OrderID, CustomerID, OrderDate, Status
    FROM Orders
    WHERE OrderDate BETWEEN @FromDate AND @ToDate
);
```

```sql
SELECT * FROM dbo.ufn_OrdersByDateRange('2024-01-01', '2024-12-31');
```

---

## Table-Valued Function: Multi-statement TVF

- Khai báo biến bảng `RETURNS @KetQua TABLE (...)`, cho phép **nhiều câu lệnh** xử lý trước khi trả kết quả
- Linh hoạt hơn Inline TVF nhưng thường **hiệu năng kém hơn** (SQL Server khó tối ưu hóa qua nhiều bước)

```sql
CREATE FUNCTION dbo.ufn_OrdersByDateRangeMS
    (@FromDate DATE, @ToDate DATE)
RETURNS @Result TABLE (OrderID INT, CustomerName NVARCHAR(100), OrderDate DATE)
AS
BEGIN
    INSERT INTO @Result
    SELECT o.OrderID, c.CustomerName, o.OrderDate
    FROM Orders o JOIN Customers c ON o.CustomerID = c.CustomerID
    WHERE o.OrderDate BETWEEN @FromDate AND @ToDate;

    RETURN;
END;
```

---

## Function vs Stored Procedure

| Tiêu chí | Function | Stored Procedure |
|---|---|---|
| Dùng trong SELECT/JOIN | Có | Không |
| Side-effect (INSERT/UPDATE/DELETE bảng thật) | Không cho phép | Có |
| Transaction (BEGIN TRAN...) | Không hỗ trợ | Có |
| TRY/CATCH đầy đủ | Hạn chế | Đầy đủ |
| Tham số OUTPUT | Không | Có |
| Gọi Procedure bên trong | Không được | Được |

- Nguyên tắc chọn: cần **trả dữ liệu để SELECT/JOIN** → Function; cần **thay đổi dữ liệu, transaction, xử lý lỗi phức tạp** → Procedure

---

# Phần 4: Trigger

> **Phần giới thiệu — không đi sâu.** Buổi học chỉ giúp bạn nhận biết khái niệm và biết khi nào nên/không nên dùng. Không có bài lab chuyên sâu về Trigger.

## Trigger là gì?

- Đoạn code **tự động thực thi** khi có sự kiện INSERT/UPDATE/DELETE xảy ra trên bảng
- Hai loại chính:
  - **AFTER trigger**: chạy **sau khi** thao tác đã xảy ra trên bảng
  - **INSTEAD OF trigger**: chạy **thay thế** thao tác gốc (thường dùng trên view)

---

## Bảng ảo inserted / deleted

- Trong trigger, SQL Server cung cấp 2 bảng ảo tạm thời:
  - `inserted`: chứa các dòng **mới** (sau INSERT/UPDATE)
  - `deleted`: chứa các dòng **cũ** (trước UPDATE/DELETE)
- UPDATE = vừa có trong `deleted` (giá trị cũ) vừa có trong `inserted` (giá trị mới)

---

## Ví dụ: AFTER INSERT trigger trừ kho tự động

```sql
CREATE TRIGGER trg_OrderDetails_AfterInsert
ON OrderDetails
AFTER INSERT
AS
BEGIN
    SET NOCOUNT ON;

    UPDATE p
    SET p.StockQty = p.StockQty - i.Quantity
    FROM Products p
    JOIN inserted i ON p.ProductID = i.ProductID;
END;
```

- Mỗi khi có dòng mới thêm vào `OrderDetails`, `StockQty` trong `Products` tự động bị trừ tương ứng
- Lưu ý: nếu Procedure `usp_CreateOrder` ở phần 2 **cũng** tự trừ kho, cần tránh trừ 2 lần (chọn 1 nơi xử lý)

---

## Cảnh báo: rủi ro khi lạm dụng Trigger

- **Khó debug**: lỗi xảy ra "âm thầm", không thấy ngay trong câu lệnh gốc
- **Ẩn logic nghiệp vụ**: dev khác không biết INSERT đơn giản lại kéo theo nhiều thay đổi khác
- **Ảnh hưởng performance**: trigger chạy trong cùng transaction với câu lệnh gốc, làm chậm thao tác
- **Trigger lồng nhau** (trigger gọi trigger khác) càng khó kiểm soát
- Nguyên tắc: chỉ dùng Trigger cho việc **thực sự cần tự động** (audit log, ràng buộc phức tạp), ưu tiên Procedure khi có thể

---

# Tổng kết Buổi 3

- **Stored Procedure**: đóng gói logic, tham số IN/OUT, RETURN, TRY/CATCH, transaction — công cụ chính để xử lý nghiệp vụ an toàn
- **Function**: Scalar và Table-Valued, dùng được trong SELECT/JOIN, nhưng không side-effect, không transaction
- **Trigger**: tự động hóa theo sự kiện bảng, mạnh nhưng dễ lạm dụng — dùng thận trọng

## Preview Buổi 4: SQL Server Agent Job, Security, Performance Tuning

- **SQL Server Agent Job**: lập lịch chạy tự động (backup, xử lý batch định kỳ...)
- **Security**: login, user, role, phân quyền chi tiết
- **Performance Tuning**: đọc execution plan, index tuning, tối ưu câu lệnh chậm
