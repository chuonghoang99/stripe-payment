# Lab Buổi 3: Stored Procedure, Function & Trigger

**Mục tiêu:** Thực hành viết Stored Procedure (tham số IN/OUT, TRY/CATCH, transaction) và Function (Scalar, Table-Valued) trên CSDL CompanyDB, đủ để áp dụng vào các tình huống xử lý nghiệp vụ thực tế.

---

## ĐỀ BÀI

### Bài 1 — Procedure cơ bản, 1 tham số
Viết Stored Procedure `usp_GetEmployeesByDepartment` nhận vào `@DepartmentID INT`, trả về danh sách nhân viên (`EmployeeID`, `FullName`, `Email`, `HireDate`, `Salary`) thuộc phòng ban đó, sắp xếp theo `HireDate` tăng dần.

### Bài 2 — Procedure nhiều tham số + giá trị mặc định
Viết Stored Procedure `usp_GetOrdersByStatus` nhận `@Status NVARCHAR(20)` với giá trị mặc định là `'Pending'`, trả về danh sách đơn hàng (`OrderID`, `CustomerID`, `OrderDate`, `Status`) có trạng thái tương ứng. Gọi thử procedure không truyền tham số và có truyền tham số.

### Bài 3 — Tham số OUTPUT
Viết Stored Procedure `usp_GetCustomerOrderStats` nhận `@CustomerID INT`, trả về 2 tham số OUTPUT: `@TotalOrders INT` (tổng số đơn hàng) và `@TotalAmount MONEY` (tổng giá trị tất cả đơn hàng, tính từ `OrderDetails`). Viết đoạn code gọi procedure và in kết quả ra bằng `SELECT`.

### Bài 4 — RETURN + kiểm tra điều kiện
Viết Stored Procedure `usp_CheckStockAvailability` nhận `@ProductID INT`, `@Quantity INT`. Nếu `StockQty` trong kho đủ để đáp ứng `@Quantity` thì `RETURN 1`, ngược lại `RETURN 0`. Viết đoạn code gọi procedure, lưu giá trị trả về vào biến và in ra thông báo phù hợp ("Đủ hàng" / "Không đủ hàng").

### Bài 5 — TRY/CATCH cơ bản
Viết Stored Procedure `usp_UpdateEmployeeSalary` nhận `@EmployeeID INT`, `@NewSalary MONEY`. Dùng TRY/CATCH: nếu `@NewSalary <= 0` thì dùng `THROW` ném lỗi tùy chỉnh (thông báo "Lương phải lớn hơn 0"); nếu `@EmployeeID` không tồn tại thì ném lỗi "Không tìm thấy nhân viên"; nếu hợp lệ thì cập nhật `Salary`.

### Bài 6 — TRY/CATCH + Transaction (bắt buộc)
Viết Stored Procedure `usp_CancelOrder` nhận `@OrderID INT`. Yêu cầu:
- Trong 1 transaction: cập nhật `Status = 'Cancelled'` trong `Orders`, đồng thời cộng trả lại `StockQty` trong `Products` cho từng dòng `OrderDetails` thuộc đơn hàng đó.
- Nếu đơn hàng không tồn tại hoặc đã ở trạng thái `'Cancelled'`, dùng `THROW` báo lỗi và không thực hiện thay đổi nào.
- Dùng `BEGIN TRY/CATCH` kết hợp `ROLLBACK TRAN` khi có lỗi.

### Bài 7 — Scalar Function
Viết Scalar Function `ufn_GetOrderTotalAmount(@OrderID INT)` trả về `MONEY`, tính tổng giá trị đơn hàng (`SUM(Quantity * UnitPrice)` từ `OrderDetails`). Dùng function này trong 1 câu `SELECT` liệt kê tất cả đơn hàng kèm tổng giá trị, sắp xếp giảm dần theo tổng giá trị.

### Bài 8 — Table-Valued Function (Inline)
Viết Inline Table-Valued Function `ufn_TopCustomersByOrderCount(@TopN INT)` trả về danh sách `@TopN` khách hàng có số lượng đơn hàng nhiều nhất (`CustomerID`, `CustomerName`, `TotalOrders`). Gợi ý: dùng `SELECT TOP (@TopN) ... ORDER BY COUNT(*) DESC`.

### Bài 9 — (Tùy chọn - nâng cao) Trigger
Viết trigger `trg_Orders_PreventDeleteCompleted` trên bảng `Orders`, loại `INSTEAD OF DELETE`: nếu đơn hàng bị xóa có `Status = 'Completed'` thì **ngăn không cho xóa** (dùng `RAISERROR`/`THROW`), các đơn hàng khác thì cho phép xóa bình thường. Gợi ý: kiểm tra qua bảng ảo `deleted`.

---

## ĐÁP ÁN

### Bài 1

```sql
CREATE PROCEDURE dbo.usp_GetEmployeesByDepartment
    @DepartmentID INT
AS
BEGIN
    SET NOCOUNT ON;

    SELECT EmployeeID, FullName, Email, HireDate, Salary
    FROM Employees
    WHERE DepartmentID = @DepartmentID
    ORDER BY HireDate ASC;
END;
GO

EXEC dbo.usp_GetEmployeesByDepartment @DepartmentID = 1;
```

*Giải thích:* Procedure cơ bản với 1 tham số IN, `SET NOCOUNT ON` để tránh trả về thông báo số dòng bị ảnh hưởng, giúp giảm nhiễu ở client.

---

### Bài 2

```sql
CREATE PROCEDURE dbo.usp_GetOrdersByStatus
    @Status NVARCHAR(20) = 'Pending'
AS
BEGIN
    SET NOCOUNT ON;

    SELECT OrderID, CustomerID, OrderDate, Status
    FROM Orders
    WHERE Status = @Status;
END;
GO

-- Gọi không truyền tham số -> dùng mặc định 'Pending'
EXEC dbo.usp_GetOrdersByStatus;

-- Gọi có truyền tham số
EXEC dbo.usp_GetOrdersByStatus @Status = 'Completed';
```

*Giải thích:* Khi tham số có giá trị mặc định, nơi gọi có thể bỏ qua tham số đó; SQL Server tự dùng giá trị mặc định đã khai báo.

---

### Bài 3

```sql
CREATE PROCEDURE dbo.usp_GetCustomerOrderStats
    @CustomerID  INT,
    @TotalOrders INT   OUTPUT,
    @TotalAmount MONEY OUTPUT
AS
BEGIN
    SET NOCOUNT ON;

    SELECT @TotalOrders = COUNT(DISTINCT o.OrderID),
           @TotalAmount = ISNULL(SUM(od.Quantity * od.UnitPrice), 0)
    FROM Orders o
    LEFT JOIN OrderDetails od ON od.OrderID = o.OrderID
    WHERE o.CustomerID = @CustomerID;
END;
GO

DECLARE @Orders INT, @Amount MONEY;
EXEC dbo.usp_GetCustomerOrderStats
    @CustomerID = 3,
    @TotalOrders = @Orders OUTPUT,
    @TotalAmount = @Amount OUTPUT;

SELECT @Orders AS TotalOrders, @Amount AS TotalAmount;
```

*Giải thích:* Tham số OUTPUT cho phép procedure trả nhiều giá trị vô hướng ra ngoài mà không cần dùng result set. `ISNULL` đảm bảo trả về 0 thay vì NULL khi khách hàng chưa có đơn hàng nào có chi tiết.

---

### Bài 4

```sql
CREATE PROCEDURE dbo.usp_CheckStockAvailability
    @ProductID INT,
    @Quantity  INT
AS
BEGIN
    SET NOCOUNT ON;

    IF EXISTS (
        SELECT 1 FROM Products
        WHERE ProductID = @ProductID AND StockQty >= @Quantity
    )
        RETURN 1;

    RETURN 0;
END;
GO

DECLARE @Result INT;
EXEC @Result = dbo.usp_CheckStockAvailability @ProductID = 1, @Quantity = 10;

SELECT CASE WHEN @Result = 1 THEN N'Đủ hàng' ELSE N'Không đủ hàng' END AS Message;
```

*Giải thích:* `RETURN` chỉ trả về 1 số nguyên, phù hợp làm mã trạng thái (0/1) chứ không dùng để trả dữ liệu nghiệp vụ chi tiết.

---

### Bài 5

```sql
CREATE PROCEDURE dbo.usp_UpdateEmployeeSalary
    @EmployeeID INT,
    @NewSalary  MONEY
AS
BEGIN
    SET NOCOUNT ON;

    BEGIN TRY
        IF @NewSalary <= 0
            THROW 50001, N'Lương phải lớn hơn 0.', 1;

        IF NOT EXISTS (SELECT 1 FROM Employees WHERE EmployeeID = @EmployeeID)
            THROW 50002, N'Không tìm thấy nhân viên.', 1;

        UPDATE Employees
        SET Salary = @NewSalary
        WHERE EmployeeID = @EmployeeID;
    END TRY
    BEGIN CATCH
        THROW;
    END CATCH
END;
GO

EXEC dbo.usp_UpdateEmployeeSalary @EmployeeID = 1, @NewSalary = 15000000;
```

*Giải thích:* Vì procedure không mở transaction (chỉ có 1 lệnh UPDATE) nên chưa cần ROLLBACK, nhưng vẫn nên bọc TRY/CATCH để chuẩn hóa cách báo lỗi bằng `THROW` với mã lỗi tùy chỉnh (>= 50000).

---

### Bài 6

```sql
CREATE PROCEDURE dbo.usp_CancelOrder
    @OrderID INT
AS
BEGIN
    SET NOCOUNT ON;

    BEGIN TRY
        IF NOT EXISTS (SELECT 1 FROM Orders WHERE OrderID = @OrderID)
            THROW 50003, N'Đơn hàng không tồn tại.', 1;

        IF EXISTS (SELECT 1 FROM Orders WHERE OrderID = @OrderID AND Status = 'Cancelled')
            THROW 50004, N'Đơn hàng đã bị hủy trước đó.', 1;

        BEGIN TRAN;

            UPDATE Products
            SET p.StockQty = p.StockQty + od.Quantity
            FROM Products p
            JOIN OrderDetails od ON od.ProductID = p.ProductID
            WHERE od.OrderID = @OrderID;

            UPDATE Orders
            SET Status = 'Cancelled'
            WHERE OrderID = @OrderID;

        COMMIT TRAN;
    END TRY
    BEGIN CATCH
        IF @@TRANCOUNT > 0
            ROLLBACK TRAN;
        THROW;
    END CATCH
END;
GO

EXEC dbo.usp_CancelOrder @OrderID = 5;
```

*Giải thích:* Việc kiểm tra điều kiện (đơn hàng tồn tại, chưa bị hủy) được thực hiện **trước** khi mở transaction để tránh mở/đóng transaction không cần thiết. Hai câu UPDATE (kho hàng + trạng thái đơn) nằm trong cùng 1 transaction để đảm bảo tính nhất quán: nếu 1 trong 2 lỗi, `ROLLBACK TRAN` sẽ hủy toàn bộ, tránh tình trạng kho được cộng nhưng đơn hàng chưa đổi trạng thái (hoặc ngược lại).

---

### Bài 7

```sql
CREATE FUNCTION dbo.ufn_GetOrderTotalAmount (@OrderID INT)
RETURNS MONEY
AS
BEGIN
    DECLARE @Total MONEY;

    SELECT @Total = SUM(Quantity * UnitPrice)
    FROM OrderDetails
    WHERE OrderID = @OrderID;

    RETURN ISNULL(@Total, 0);
END;
GO

SELECT OrderID, OrderDate, Status,
       dbo.ufn_GetOrderTotalAmount(OrderID) AS TotalAmount
FROM Orders
ORDER BY dbo.ufn_GetOrderTotalAmount(OrderID) DESC;
```

*Giải thích:* Scalar Function trả về đúng 1 giá trị MONEY, dùng trực tiếp trong `SELECT` và `ORDER BY` như một cột bình thường — điều mà Stored Procedure không làm được.

---

### Bài 8

```sql
CREATE FUNCTION dbo.ufn_TopCustomersByOrderCount (@TopN INT)
RETURNS TABLE
AS
RETURN
(
    SELECT TOP (@TopN)
           c.CustomerID,
           c.CustomerName,
           COUNT(o.OrderID) AS TotalOrders
    FROM Customers c
    JOIN Orders o ON o.CustomerID = c.CustomerID
    GROUP BY c.CustomerID, c.CustomerName
    ORDER BY COUNT(o.OrderID) DESC
);
GO

SELECT * FROM dbo.ufn_TopCustomersByOrderCount(5);
```

*Giải thích:* Inline TVF chỉ chứa 1 câu lệnh `RETURN (SELECT ...)`, SQL Server có thể "mở rộng" (inline) câu truy vấn này vào câu SELECT bên ngoài để tối ưu hóa, nên hiệu năng thường tốt hơn Multi-statement TVF.

---

### Bài 9 (Tùy chọn - nâng cao)

```sql
CREATE TRIGGER trg_Orders_PreventDeleteCompleted
ON Orders
INSTEAD OF DELETE
AS
BEGIN
    SET NOCOUNT ON;

    IF EXISTS (SELECT 1 FROM deleted WHERE Status = 'Completed')
    BEGIN
        THROW 50005, N'Không được xóa đơn hàng đã hoàn thành (Completed).', 1;
        RETURN;
    END

    DELETE o
    FROM Orders o
    JOIN deleted d ON d.OrderID = o.OrderID
    WHERE d.Status <> 'Completed';
END;
GO

-- Thử xóa đơn hàng đã Completed -> bị chặn
DELETE FROM Orders WHERE OrderID = 1; -- giả sử OrderID = 1 có Status = 'Completed'
```

*Giải thích:* `INSTEAD OF DELETE` chặn hoàn toàn thao tác DELETE gốc và thay bằng logic tùy chỉnh bên trong trigger. Bảng ảo `deleted` chứa các dòng **đáng lẽ** bị xóa, cho phép kiểm tra điều kiện trước khi quyết định có thực sự xóa hay không. Đây là ví dụ minh họa cho phần giới thiệu Trigger — không yêu cầu bắt buộc trong buổi học.
