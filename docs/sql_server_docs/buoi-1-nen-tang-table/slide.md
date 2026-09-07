---
marp: true
theme: default
paginate: true
---

# Buổi 1: Nền tảng SQL Server & Table

### Đào tạo SQL Server cho Dev (Buổi 1/4 - 180 phút)

Sau buổi học này bạn sẽ:

- Hiểu kiến trúc client-server của SQL Server và biết dùng SSMS cơ bản
- Chọn đúng kiểu dữ liệu khi thiết kế bảng, đặc biệt với dữ liệu tiếng Việt
- Tự viết được CREATE TABLE / ALTER TABLE / DROP TABLE với đầy đủ constraint
- Thiết lập được quan hệ 1-n giữa các bảng bằng FOREIGN KEY
- Hiểu khái niệm Clustered/Non-clustered Index và biết khi nào cần đánh index

---

## Agenda Buổi 1 (180 phút)

| # | Nội dung | Thời gian |
|---|----------|-----------|
| 1 | Giới thiệu SQL Server & SSMS | 20 phút |
| 2 | Kiểu dữ liệu & thiết kế bảng | 30 phút |
| 3 | DDL - CREATE / ALTER / DROP TABLE | 60 phút |
| 4 | Index cơ bản | 30 phút |
| 5 | Tổng kết buổi học | 10 phút |
| — | Thực hành lab | 30 phút |

---

# Phần 1: Giới thiệu SQL Server & SSMS
### (20 phút)

---

## SQL Server là gì?

- SQL Server là hệ quản trị cơ sở dữ liệu quan hệ (RDBMS) của Microsoft
- Hoạt động theo mô hình **Client - Server**:
  - **Server**: SQL Server Database Engine, lưu trữ & xử lý dữ liệu
  - **Client**: SSMS, ứng dụng, script... gửi câu lệnh T-SQL đến server
- Client kết nối đến server qua **connection string** (server name, authentication)
- Một server có thể phục vụ nhiều client cùng lúc, nhiều database cùng lúc

---

## Các Edition phổ biến

| Edition | Mục đích | Chi phí |
|---------|----------|---------|
| **Express** | Ứng dụng nhỏ, học tập, giới hạn dung lượng | Miễn phí |
| **Developer** | Phát triển/test, đầy đủ tính năng Enterprise | Miễn phí (không dùng production) |
| **Standard** | Ứng dụng vừa, tính năng cơ bản | Có phí |
| **Enterprise** | Hệ thống lớn, đầy đủ tính năng nâng cao | Có phí cao |

- Khi học và thực hành: dùng **Developer Edition** hoặc **Express**

---

## SSMS (SQL Server Management Studio)

Công cụ GUI chính để làm việc với SQL Server:

- **Object Explorer**: cây thư mục xem Database, Table, View, Stored Procedure...
- **New Query**: cửa sổ soạn thảo và chạy câu lệnh T-SQL
- **Connect**: kết nối đến 1 server bằng Server name + Authentication
  - Windows Authentication (dùng tài khoản Windows đang đăng nhập)
  - SQL Server Authentication (username/password riêng)

> Thao tác demo: mở SSMS, kết nối server, mở New Query, chạy `SELECT @@VERSION`

---

## Khái niệm Database & Schema

- **Database**: đơn vị lưu trữ độc lập, chứa toàn bộ bảng/view/procedure của 1 ứng dụng
  - Ví dụ: `CompanyDB` là database dùng xuyên suốt 4 buổi học
- **Schema**: không gian tên (namespace) để nhóm các object bên trong database
  - Mặc định mọi object được tạo trong schema **`dbo`**
  - Tên đầy đủ của bảng: `dbo.Employees` (thường viết tắt là `Employees`)

```sql
-- Chọn database làm việc trước khi thao tác
USE CompanyDB;
GO

SELECT * FROM dbo.Employees;
```

---

# Phần 2: Kiểu dữ liệu & thiết kế bảng
### (30 phút)

---

## Tổng quan các nhóm kiểu dữ liệu

| Nhóm | Kiểu tiêu biểu |
|------|----------------|
| Số nguyên | `INT`, `BIGINT` |
| Số thập phân | `DECIMAL(p,s)` |
| Chuỗi ký tự | `VARCHAR(n)`, `NVARCHAR(n)`, `CHAR(n)` |
| Ngày giờ | `DATE`, `DATETIME`, `DATETIME2` |
| Khác | `BIT` (boolean), ... |

- Chọn đúng kiểu dữ liệu giúp tiết kiệm dung lượng và tránh lỗi logic

---

## Kiểu dữ liệu số

- **`INT`**: số nguyên, khoảng ±2.1 tỷ - dùng cho ID, số lượng
- **`BIGINT`**: số nguyên lớn hơn INT - dùng khi ID có thể vượt 2 tỷ dòng
- **`DECIMAL(p, s)`**: số thập phân chính xác - **luôn dùng cho tiền tệ**
  - `p` = tổng số chữ số, `s` = số chữ số sau dấu thập phân
  - Ví dụ: `DECIMAL(12,2)` → tối đa 12 chữ số, 2 số lẻ

```sql
-- Employees.Salary và Products.UnitPrice trong CompanyDB
Salary    DECIMAL(12,2)   -- ví dụ: 35000000.00
UnitPrice DECIMAL(10,2)   -- ví dụ: 15500000.00
```

> Lưu ý: KHÔNG dùng `FLOAT`/`REAL` cho tiền tệ vì có sai số làm tròn

---

## Kiểu dữ liệu chuỗi: VARCHAR vs NVARCHAR

- **`CHAR(n)`**: độ dài **cố định** n ký tự, luôn chiếm đủ n byte
- **`VARCHAR(n)`**: độ dài **thay đổi**, chỉ lưu ASCII (1 byte/ký tự)
- **`NVARCHAR(n)`**: độ dài thay đổi, hỗ trợ **Unicode** (2 byte/ký tự)

```sql
-- SAI: VARCHAR không lưu đúng tiếng Việt có dấu
FullName VARCHAR(150)   -- 'Nguyễn Văn An' có thể bị lỗi/mất dấu

-- ĐÚNG: dùng NVARCHAR cho mọi text tiếng Việt
FullName NVARCHAR(150)  -- lưu đúng 'Nguyễn Văn An'
```

> Quy tắc: dữ liệu có khả năng chứa tiếng Việt (tên, địa chỉ...) → luôn dùng `NVARCHAR`.
> Khi INSERT chuỗi Unicode phải thêm tiền tố `N`: `N'Nguyễn Văn An'`

---

## Kiểu dữ liệu ngày giờ

| Kiểu | Độ chính xác | Dùng khi |
|------|---------------|----------|
| `DATE` | Chỉ ngày (yyyy-mm-dd) | Ngày sinh, ngày thuê (HireDate) |
| `DATETIME` | Ngày + giờ, làm tròn ~3ms | Cần cả giờ, tương thích hệ cũ |
| `DATETIME2` | Ngày + giờ, chính xác cao hơn | Nên ưu tiên dùng cho hệ thống mới |

```sql
HireDate  DATE      NOT NULL,   -- Employees.HireDate: '2018-01-15'
OrderDate DATETIME  NOT NULL DEFAULT GETDATE()  -- Orders.OrderDate
```

---

## Kiểu khác: BIT, NULL, DEFAULT

- **`BIT`**: kiểu boolean, chỉ nhận giá trị `0`, `1` hoặc `NULL`
  - Ví dụ dùng cho cột `IsActive`, `IsDeleted`
- **`NULL`**: đại diện cho "chưa có giá trị / không xác định"
  - `ManagerID` của nhân viên cao nhất (không có quản lý) → để `NULL`
- **`DEFAULT`**: giá trị tự động gán khi INSERT không chỉ định cột đó

```sql
StockQty  INT NOT NULL DEFAULT 0,              -- Products.StockQty
Status    NVARCHAR(20) NOT NULL DEFAULT 'Pending',  -- Orders.Status
OrderDate DATETIME NOT NULL DEFAULT GETDATE()  -- tự lấy thời gian hiện tại
```

---

## Ví dụ tổng hợp: thiết kế bảng Employees

```sql
CREATE TABLE Employees (
    EmployeeID   INT IDENTITY(1,1) PRIMARY KEY,
    FullName     NVARCHAR(150) NOT NULL,   -- Unicode cho tên tiếng Việt
    Email        NVARCHAR(150) NOT NULL,
    DepartmentID INT NOT NULL,
    ManagerID    INT NULL,                 -- có thể không có quản lý
    HireDate     DATE NOT NULL,            -- chỉ cần ngày
    Salary       DECIMAL(12,2) NOT NULL    -- tiền tệ chính xác
);
```

- Mỗi cột đều được chọn kiểu dữ liệu dựa trên bản chất dữ liệu thực tế

---

# Phần 3: DDL - CREATE / ALTER / DROP TABLE
### (60 phút)

---

## CREATE TABLE - cú pháp cơ bản

```sql
CREATE TABLE TenBang (
    TenCot1 KieuDuLieu [ràng buộc],
    TenCot2 KieuDuLieu [ràng buộc],
    ...
);
GO
```

- Mỗi câu lệnh DDL trong SSMS thường kết thúc bằng `GO` (batch separator)
- `GO` không phải từ khóa T-SQL, mà là tín hiệu cho SSMS biết "chạy batch đến đây"

```sql
CREATE TABLE Departments (
    DepartmentID   INT IDENTITY(1,1) PRIMARY KEY,
    DepartmentName NVARCHAR(100) NOT NULL,
    ManagerID      INT NULL
);
GO
```

---

## PRIMARY KEY & IDENTITY

- **`PRIMARY KEY`**: định danh duy nhất cho mỗi dòng, không được NULL, không trùng
- **`IDENTITY(seed, increment)`**: tự động sinh số tăng dần, không cần INSERT giá trị

```sql
CREATE TABLE Departments (
    DepartmentID INT IDENTITY(1,1) PRIMARY KEY,  -- bắt đầu từ 1, +1 mỗi dòng
    DepartmentName NVARCHAR(100) NOT NULL
);
GO

INSERT INTO Departments (DepartmentName) VALUES (N'IT');
-- DepartmentID tự động = 1, không cần chỉ định
```

---

## FOREIGN KEY - thiết lập quan hệ 1-n

- **`FOREIGN KEY`** đảm bảo giá trị ở bảng con phải tồn tại ở bảng cha
- Thể hiện quan hệ **1-n**: 1 phòng ban có nhiều nhân viên

```sql
CREATE TABLE Employees (
    EmployeeID   INT IDENTITY(1,1) PRIMARY KEY,
    FullName     NVARCHAR(150) NOT NULL,
    DepartmentID INT NOT NULL,
    CONSTRAINT FK_Employees_Departments
        FOREIGN KEY (DepartmentID) REFERENCES Departments(DepartmentID)
);
GO
```

- 1 `Departments` → n `Employees` (mỗi Employee thuộc đúng 1 Department)
- SQL Server sẽ từ chối INSERT nếu `DepartmentID` không tồn tại trong `Departments`

---

## Constraint: CHECK

- Ràng buộc điều kiện dữ liệu hợp lệ cho 1 cột hoặc nhiều cột

```sql
CREATE TABLE Employees (
    ...
    Salary DECIMAL(12,2) NOT NULL,
    CONSTRAINT CK_Employees_Salary CHECK (Salary > 0)
);
GO

-- INSERT sau sẽ bị từ chối vì vi phạm CHECK
INSERT INTO Employees (..., Salary) VALUES (..., -5000000);
-- Lỗi: The INSERT statement conflicted with the CHECK constraint
```

---

## Constraint: UNIQUE, DEFAULT, NOT NULL

- **`UNIQUE`**: giá trị không được trùng lặp (khác PRIMARY KEY: cho phép NULL)
- **`NOT NULL`**: bắt buộc phải có giá trị
- **`DEFAULT`**: giá trị mặc định khi không chỉ định lúc INSERT

```sql
CREATE TABLE Employees (
    EmployeeID INT IDENTITY(1,1) PRIMARY KEY,
    Email      NVARCHAR(150) NOT NULL UNIQUE,   -- không trùng email
    HireDate   DATE NOT NULL,
    Salary     DECIMAL(12,2) NOT NULL
);
GO
```

---

## Ví dụ tổng hợp constraint: bảng Products

```sql
CREATE TABLE Products (
    ProductID   INT IDENTITY(1,1) PRIMARY KEY,
    ProductName NVARCHAR(150) NOT NULL,
    Category    NVARCHAR(100) NULL,
    UnitPrice   DECIMAL(10,2) NOT NULL,
    StockQty    INT NOT NULL DEFAULT 0,
    CONSTRAINT CK_Products_UnitPrice CHECK (UnitPrice >= 0)
);
GO
```

- `NOT NULL` cho các cột bắt buộc, `DEFAULT 0` cho tồn kho mới, `CHECK` chặn giá âm

---

## Quan hệ 1-n thứ hai: Orders - OrderDetails

- 1 đơn hàng (`Orders`) có nhiều dòng chi tiết (`OrderDetails`)

```sql
CREATE TABLE Orders (
    OrderID    INT IDENTITY(1,1) PRIMARY KEY,
    CustomerID INT NOT NULL,
    EmployeeID INT NOT NULL,
    OrderDate  DATETIME NOT NULL DEFAULT GETDATE(),
    Status     NVARCHAR(20) NOT NULL DEFAULT 'Pending',
    CONSTRAINT FK_Orders_Customers FOREIGN KEY (CustomerID) REFERENCES Customers(CustomerID),
    CONSTRAINT FK_Orders_Employees FOREIGN KEY (EmployeeID) REFERENCES Employees(EmployeeID)
);
GO

CREATE TABLE OrderDetails (
    OrderDetailID INT IDENTITY(1,1) PRIMARY KEY,
    OrderID       INT NOT NULL,
    ProductID     INT NOT NULL,
    Quantity      INT NOT NULL,
    CONSTRAINT FK_OrderDetails_Orders FOREIGN KEY (OrderID) REFERENCES Orders(OrderID),
    CONSTRAINT CK_OrderDetails_Quantity CHECK (Quantity > 0)
);
GO
```

---

## ALTER TABLE - thêm cột

```sql
-- Thêm cột mới vào bảng đã tồn tại
ALTER TABLE Customers
ADD Phone NVARCHAR(20) NULL;
GO

-- Thêm cột kèm giá trị mặc định cho các dòng đã có
ALTER TABLE Products
ADD IsDiscontinued BIT NOT NULL DEFAULT 0;
GO
```

- Cột mới áp dụng cho mọi dòng đang tồn tại trong bảng
- Nếu thêm cột `NOT NULL` mà không có `DEFAULT`, câu lệnh sẽ lỗi khi bảng đã có dữ liệu

---

## ALTER TABLE - sửa & xóa cột

```sql
-- Sửa kiểu dữ liệu của cột đã tồn tại
ALTER TABLE Customers
ALTER COLUMN Phone NVARCHAR(30) NULL;
GO

-- Xóa 1 cột không còn cần dùng
ALTER TABLE Customers
DROP COLUMN Phone;
GO

-- Xóa 1 constraint
ALTER TABLE Employees
DROP CONSTRAINT CK_Employees_Salary;
GO
```

> Lưu ý: không thể ALTER COLUMN nếu cột đang được dùng trong INDEX hoặc constraint liên quan - phải xóa trước

---

## DROP TABLE

```sql
-- Xóa hẳn bảng và toàn bộ dữ liệu bên trong
DROP TABLE OrderDetails;
GO
```

- Phải xóa (hoặc gỡ) các bảng/ràng buộc con trước khi xóa bảng cha
- Ví dụ: phải `DROP TABLE OrderDetails` trước khi `DROP TABLE Orders`
  (vì `OrderDetails` có FOREIGN KEY tham chiếu đến `Orders`)

```sql
-- Thứ tự đúng khi cần xóa cả 2 bảng có quan hệ FK
DROP TABLE OrderDetails;  -- bảng con trước
DROP TABLE Orders;        -- bảng cha sau
GO
```

---

# Phần 4: Index cơ bản
### (30 phút)

---

## Index là gì? Clustered vs Non-clustered

- **Index** giúp SQL Server tìm dữ liệu nhanh hơn thay vì quét toàn bảng
- **Clustered Index**: quyết định **thứ tự vật lý** lưu trữ dữ liệu trên đĩa
  - Mỗi bảng chỉ có **tối đa 1** clustered index
- **Non-clustered Index**: cấu trúc riêng biệt, chứa con trỏ trỏ về dòng dữ liệu gốc
  - Mỗi bảng có thể có **nhiều** non-clustered index

> Ví von: Clustered index giống mục lục sách được sắp xếp theo thứ tự trang thật;
> Non-clustered index giống mục lục ở cuối sách, trỏ đến số trang.

---

## Vì sao PRIMARY KEY mặc định là Clustered?

```sql
CREATE TABLE Departments (
    DepartmentID INT IDENTITY(1,1) PRIMARY KEY,  -- tự động tạo Clustered Index
    DepartmentName NVARCHAR(100) NOT NULL
);
GO
```

- Khi tạo `PRIMARY KEY`, SQL Server **mặc định** tạo luôn 1 Clustered Index trên cột đó
- Vì `PRIMARY KEY` là duy nhất & thường dùng để tra cứu/join → hợp lý để làm thứ tự lưu trữ chính
- Có thể đổi lại bằng `PRIMARY KEY NONCLUSTERED` nếu có lý do đặc biệt (nâng cao, không học ở buổi này)

---

## Cú pháp CREATE INDEX cơ bản

```sql
-- Tạo Non-clustered Index trên 1 cột
CREATE NONCLUSTERED INDEX IX_Employees_DepartmentID
ON Employees (DepartmentID);
GO

-- Tạo Index trên nhiều cột
CREATE NONCLUSTERED INDEX IX_Orders_CustomerID_OrderDate
ON Orders (CustomerID, OrderDate);
GO
```

- Đặt tên index theo quy ước dễ nhận biết: `IX_TênBảng_TênCột`

---

## Khi nào nên đánh Index?

- Cột thường xuất hiện trong `WHERE`, `JOIN`, `ORDER BY` → nên cân nhắc đánh index
  - Ví dụ: `Employees.DepartmentID` (dùng để JOIN với Departments)
  - Ví dụ: `Orders.CustomerID` (dùng để tra cứu đơn hàng theo khách hàng)
- Không nên đánh index tràn lan:
  - Mỗi index tốn thêm dung lượng lưu trữ
  - Mỗi lần INSERT/UPDATE/DELETE phải cập nhật thêm index → chậm hơn
- Đây chỉ là khái niệm nền tảng - phần tối ưu hiệu năng chuyên sâu sẽ học ở **Buổi 4**

---

# Tổng kết Buổi 1
### (10 phút)

---

## Recap - những điểm chính

- SQL Server hoạt động theo mô hình **Client-Server**, thao tác chính qua **SSMS**
- Chọn đúng kiểu dữ liệu: **NVARCHAR** cho tiếng Việt, **DECIMAL** cho tiền tệ
- **CREATE TABLE** với **PRIMARY KEY**, **FOREIGN KEY**, và các constraint (`CHECK`, `UNIQUE`, `DEFAULT`, `NOT NULL`) để đảm bảo dữ liệu hợp lệ
- **ALTER TABLE** để thêm/sửa/xóa cột khi thiết kế bảng thay đổi theo thời gian
- **Index** giúp truy vấn nhanh hơn: PRIMARY KEY mặc định là Clustered, có thể thêm Non-clustered Index cho các cột hay tra cứu

---

## Tiếp theo: Buổi 2 - Query nâng cao & View

- Các loại `JOIN` (INNER, LEFT, RIGHT, FULL) để kết hợp dữ liệu nhiều bảng
- Hàm tổng hợp (`GROUP BY`, `HAVING`), Subquery, CTE
- Tạo và sử dụng **View** để đơn giản hóa truy vấn phức tạp
- Thực hành trực tiếp trên các bảng `Orders`, `OrderDetails`, `Employees` đã tạo hôm nay

### Chuẩn bị: đảm bảo CompanyDB đã chạy đúng script `setup.sql` trước buổi tiếp theo
