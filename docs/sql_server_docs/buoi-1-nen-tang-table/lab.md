# Lab Buổi 1: Nền tảng SQL Server & Table

**Mục tiêu:** Thực hành tạo bảng, thiết lập constraint, quan hệ khóa ngoại và index cơ bản trên cơ sở dữ liệu `CompanyDB`, đồng thời rèn tư duy tự thiết kế bảng mới phù hợp với mô hình dữ liệu hiện có.

> Trước khi bắt đầu, đảm bảo đã chạy script `00-du-lieu-mau/setup.sql` để có sẵn `CompanyDB` với các bảng `Departments`, `Employees`, `Customers`, `Products`, `Orders`, `OrderDetails`.

---

## ĐỀ BÀI

### Bài 1 (Dễ) - Tạo bảng mới: Suppliers

Công ty muốn quản lý thêm thông tin nhà cung cấp sản phẩm. Hãy tự thiết kế và tạo bảng `Suppliers` với tối thiểu các cột sau:

- `SupplierID`: khóa chính, tự tăng
- `SupplierName`: tên nhà cung cấp, bắt buộc nhập, hỗ trợ tiếng Việt
- `Email`: không được trùng giữa các nhà cung cấp
- `City`: có thể để trống

### Bài 2 (Dễ - Trung bình) - ALTER TABLE thêm cột và FOREIGN KEY

Mỗi sản phẩm trong bảng `Products` cần biết được cung cấp bởi nhà cung cấp nào.

- Thêm cột `SupplierID` (kiểu `INT`, cho phép NULL) vào bảng `Products`
- Thiết lập ràng buộc khóa ngoại từ `Products.SupplierID` tham chiếu đến `Suppliers.SupplierID`

### Bài 3 (Trung bình) - Thêm CHECK constraint cho bảng đã tồn tại

Cột `StockQty` của bảng `Products` hiện chưa có ràng buộc chặn giá trị âm.

- Viết câu lệnh `ALTER TABLE` để thêm 1 `CHECK` constraint đảm bảo `StockQty >= 0`
- Đặt tên constraint theo quy ước `CK_TenBang_TenCot`

### Bài 4 (Trung bình) - ALTER TABLE thêm nhiều cột

Bảng `Employees` cần bổ sung thông tin liên hệ và trạng thái làm việc.

- Thêm cột `PhoneNumber` kiểu chuỗi phù hợp, cho phép NULL
- Thêm cột `IsActive` kiểu boolean, bắt buộc có giá trị, mặc định là "đang làm việc" (1)

### Bài 5 (Trung bình - Khó) - Tự thiết kế bảng: Attendance (chấm công)

Công ty muốn theo dõi chấm công hàng ngày của nhân viên. Hãy tự thiết kế và tạo bảng `Attendance` thể hiện đúng quan hệ **1-n** với `Employees` (1 nhân viên có nhiều bản ghi chấm công), với tối thiểu:

- `AttendanceID`: khóa chính, tự tăng
- `EmployeeID`: khóa ngoại tham chiếu `Employees`, bắt buộc
- `WorkDate`: ngày chấm công, bắt buộc
- `Status`: trạng thái, chỉ được nhận 1 trong 3 giá trị `'Present'`, `'Absent'`, `'Leave'`, mặc định là `'Present'`

### Bài 6 (Khó) - ALTER COLUMN, DROP COLUMN và thứ tự DROP TABLE

a. Đổi kiểu dữ liệu cột `Orders.Status` từ `NVARCHAR(20)` thành `NVARCHAR(30)` để dự phòng các trạng thái dài hơn trong tương lai.

b. Giả sử bảng `Suppliers` ở Bài 1 có thêm cột `Fax NVARCHAR(20) NULL` nhưng không còn ai dùng nữa - hãy viết câu lệnh xóa cột này.

c. Nếu muốn xóa hoàn toàn 2 bảng `Orders` và `OrderDetails`, cần `DROP TABLE` theo thứ tự nào để không bị lỗi vi phạm khóa ngoại? Viết đúng thứ tự 2 câu lệnh (chỉ viết câu lệnh minh họa, **không cần chạy thật** vì dữ liệu này còn dùng cho các buổi sau).

### Bài 7 (Khó) - Tạo Index hỗ trợ truy vấn thường gặp

Hệ thống thường xuyên chạy các truy vấn:

- Tra cứu danh sách đơn hàng theo `CustomerID`
- Tra cứu danh sách nhân viên theo `DepartmentID`
- Tra cứu bản ghi chấm công theo `EmployeeID` và `WorkDate` (bảng `Attendance` ở Bài 5)

Hãy tạo các Non-clustered Index phù hợp cho 3 trường hợp trên và giải thích ngắn gọn vì sao chọn cột đó để đánh index.

### Bài 8 (Khó nhất - Tổng hợp) - Tự thiết kế bảng: OrderStatusHistory

Công ty muốn lưu lại lịch sử thay đổi trạng thái của đơn hàng (ví dụ: từ `Pending` sang `Completed`) để phục vụ tra cứu sau này. Hãy tự thiết kế và tạo bảng `OrderStatusHistory` đáp ứng:

- Thể hiện quan hệ 1-n với `Orders` (1 đơn hàng có nhiều lần đổi trạng thái)
- Ghi nhận nhân viên (`Employees`) nào thực hiện thay đổi
- Lưu trạng thái mới (`NVARCHAR`) và thời điểm thay đổi, tự động lấy thời gian hiện tại nếu không chỉ định
- Đầy đủ `PRIMARY KEY`, các `FOREIGN KEY` cần thiết, và tự tạo thêm 1 index hỗ trợ tra cứu nhanh lịch sử theo `OrderID`

---

## ĐÁP ÁN

### Đáp án Bài 1

```sql
CREATE TABLE Suppliers (
    SupplierID   INT IDENTITY(1,1) PRIMARY KEY,
    SupplierName NVARCHAR(150) NOT NULL,
    Email        NVARCHAR(150) NULL UNIQUE,
    City         NVARCHAR(100) NULL
);
GO
```

*Giải thích:* `NVARCHAR` được dùng cho `SupplierName` và `City` vì có thể chứa tiếng Việt có dấu. `Email` dùng `UNIQUE` (không phải `PRIMARY KEY`) vì vẫn cho phép để trống (`NULL`) nhưng nếu có giá trị thì không được trùng.

### Đáp án Bài 2

```sql
ALTER TABLE Products
ADD SupplierID INT NULL;
GO

ALTER TABLE Products
ADD CONSTRAINT FK_Products_Suppliers
    FOREIGN KEY (SupplierID) REFERENCES Suppliers(SupplierID);
GO
```

*Giải thích:* Thêm cột trước rồi mới thêm constraint FK ở bước riêng để dễ kiểm soát lỗi. Cột để `NULL` vì có thể có sản phẩm chưa xác định nhà cung cấp (ví dụ sản phẩm cũ nhập trước khi có tính năng này).

### Đáp án Bài 3

```sql
ALTER TABLE Products
ADD CONSTRAINT CK_Products_StockQty CHECK (StockQty >= 0);
GO
```

*Giải thích:* Dùng `ADD CONSTRAINT` (không phải `ADD COLUMN`) vì chỉ thêm ràng buộc cho cột đã tồn tại, không thêm cột mới.

### Đáp án Bài 4

```sql
ALTER TABLE Employees
ADD PhoneNumber NVARCHAR(20) NULL,
    IsActive BIT NOT NULL DEFAULT 1;
GO
```

*Giải thích:* `IsActive` bắt buộc phải có `DEFAULT` vì được khai báo `NOT NULL` trên bảng đã có sẵn dữ liệu - nếu không có `DEFAULT`, SQL Server sẽ không biết gán giá trị gì cho các dòng hiện có và câu lệnh sẽ lỗi.

### Đáp án Bài 5

```sql
CREATE TABLE Attendance (
    AttendanceID INT IDENTITY(1,1) PRIMARY KEY,
    EmployeeID   INT NOT NULL,
    WorkDate     DATE NOT NULL,
    Status       NVARCHAR(20) NOT NULL DEFAULT 'Present',
    CONSTRAINT FK_Attendance_Employees
        FOREIGN KEY (EmployeeID) REFERENCES Employees(EmployeeID),
    CONSTRAINT CK_Attendance_Status
        CHECK (Status IN ('Present', 'Absent', 'Leave'))
);
GO
```

*Giải thích:* `FOREIGN KEY` trên `EmployeeID` thể hiện đúng quan hệ 1-n (1 Employee - nhiều Attendance). `CHECK ... IN (...)` giới hạn `Status` chỉ nhận 3 giá trị hợp lệ, tránh nhập sai chính tả hay giá trị rác.

### Đáp án Bài 6

```sql
-- a. Đổi kiểu dữ liệu cột Status
ALTER TABLE Orders
ALTER COLUMN Status NVARCHAR(30) NOT NULL;
GO

-- b. Xóa cột Fax không còn dùng
ALTER TABLE Suppliers
DROP COLUMN Fax;
GO

-- c. Thứ tự DROP TABLE đúng (bảng con trước, bảng cha sau)
DROP TABLE OrderDetails;  -- OrderDetails có FK trỏ đến Orders -> phải xóa trước
DROP TABLE Orders;        -- Orders là bảng cha -> xóa sau
GO
```

*Giải thích:* Khi `ALTER COLUMN` phải khai báo lại đầy đủ ràng buộc `NOT NULL` nếu cột đang có, nếu không SQL Server sẽ đổi cột thành cho phép NULL. Với `DROP TABLE`, SQL Server không cho xóa bảng cha khi vẫn còn bảng con tham chiếu đến nó qua `FOREIGN KEY`, nên phải xóa `OrderDetails` (con) trước `Orders` (cha).

### Đáp án Bài 7

```sql
CREATE NONCLUSTERED INDEX IX_Orders_CustomerID
ON Orders (CustomerID);
GO

CREATE NONCLUSTERED INDEX IX_Employees_DepartmentID
ON Employees (DepartmentID);
GO

CREATE NONCLUSTERED INDEX IX_Attendance_EmployeeID_WorkDate
ON Attendance (EmployeeID, WorkDate);
GO
```

*Giải thích:* Cả 3 cột đều là khóa ngoại thường xuyên xuất hiện trong `WHERE`/`JOIN`, nên đánh index giúp SQL Server tìm dòng phù hợp nhanh hơn thay vì quét toàn bảng. `Attendance` dùng index trên cả 2 cột (`EmployeeID`, `WorkDate`) vì truy vấn thường lọc đồng thời theo nhân viên và khoảng ngày.

### Đáp án Bài 8

```sql
CREATE TABLE OrderStatusHistory (
    HistoryID    INT IDENTITY(1,1) PRIMARY KEY,
    OrderID      INT NOT NULL,
    EmployeeID   INT NOT NULL,
    NewStatus    NVARCHAR(30) NOT NULL,
    ChangedAt    DATETIME NOT NULL DEFAULT GETDATE(),
    CONSTRAINT FK_OrderStatusHistory_Orders
        FOREIGN KEY (OrderID) REFERENCES Orders(OrderID),
    CONSTRAINT FK_OrderStatusHistory_Employees
        FOREIGN KEY (EmployeeID) REFERENCES Employees(EmployeeID)
);
GO

CREATE NONCLUSTERED INDEX IX_OrderStatusHistory_OrderID
ON OrderStatusHistory (OrderID);
GO
```

*Giải thích:* Bảng thể hiện quan hệ 1-n với `Orders` (1 đơn hàng có nhiều lần đổi trạng thái) và ghi nhận `EmployeeID` để biết ai thực hiện. `ChangedAt` dùng `DEFAULT GETDATE()` để tự động ghi thời điểm nếu không truyền giá trị, tương tự cách `Orders.OrderDate` đã làm. Index trên `OrderID` giúp tra cứu nhanh toàn bộ lịch sử của 1 đơn hàng cụ thể - đây chính là truy vấn phổ biến nhất trên bảng lịch sử này.
