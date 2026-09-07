/* ============================================================
   CompanyDB - CO SO DU LIEU MAU DUNG XUYEN SUOT 4 BUOI DAO TAO
   Chay toan bo script nay 1 lan duy nhat truoc Buoi 1.
   ============================================================ */

IF DB_ID('CompanyDB') IS NOT NULL
BEGIN
    ALTER DATABASE CompanyDB SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE CompanyDB;
END
GO

CREATE DATABASE CompanyDB;
GO

USE CompanyDB;
GO

/* ---------- BANG (TABLES) ---------- */

CREATE TABLE Departments (
    DepartmentID   INT IDENTITY(1,1) PRIMARY KEY,
    DepartmentName NVARCHAR(100) NOT NULL,
    ManagerID      INT NULL
);
GO

CREATE TABLE Employees (
    EmployeeID     INT IDENTITY(1,1) PRIMARY KEY,
    FullName       NVARCHAR(150) NOT NULL,
    Email          NVARCHAR(150) NOT NULL UNIQUE,
    DepartmentID   INT NOT NULL,
    ManagerID      INT NULL,
    HireDate       DATE NOT NULL,
    Salary         DECIMAL(12,2) NOT NULL,
    CONSTRAINT FK_Employees_Departments FOREIGN KEY (DepartmentID) REFERENCES Departments(DepartmentID),
    CONSTRAINT FK_Employees_Manager FOREIGN KEY (ManagerID) REFERENCES Employees(EmployeeID),
    CONSTRAINT CK_Employees_Salary CHECK (Salary > 0)
);
GO

ALTER TABLE Departments
    ADD CONSTRAINT FK_Departments_Manager FOREIGN KEY (ManagerID) REFERENCES Employees(EmployeeID);
GO

CREATE TABLE Customers (
    CustomerID   INT IDENTITY(1,1) PRIMARY KEY,
    CustomerName NVARCHAR(150) NOT NULL,
    Email        NVARCHAR(150) NULL,
    City         NVARCHAR(100) NULL
);
GO

CREATE TABLE Products (
    ProductID   INT IDENTITY(1,1) PRIMARY KEY,
    ProductName NVARCHAR(150) NOT NULL,
    Category    NVARCHAR(100) NULL,
    UnitPrice   DECIMAL(10,2) NOT NULL,
    StockQty    INT NOT NULL DEFAULT 0,
    CONSTRAINT CK_Products_UnitPrice CHECK (UnitPrice >= 0)
);
GO

CREATE TABLE Orders (
    OrderID     INT IDENTITY(1,1) PRIMARY KEY,
    CustomerID  INT NOT NULL,
    EmployeeID  INT NOT NULL,
    OrderDate   DATETIME NOT NULL DEFAULT GETDATE(),
    Status      NVARCHAR(20) NOT NULL DEFAULT 'Pending',
    CONSTRAINT FK_Orders_Customers FOREIGN KEY (CustomerID) REFERENCES Customers(CustomerID),
    CONSTRAINT FK_Orders_Employees FOREIGN KEY (EmployeeID) REFERENCES Employees(EmployeeID)
);
GO

CREATE TABLE OrderDetails (
    OrderDetailID INT IDENTITY(1,1) PRIMARY KEY,
    OrderID       INT NOT NULL,
    ProductID     INT NOT NULL,
    Quantity      INT NOT NULL,
    UnitPrice     DECIMAL(10,2) NOT NULL,
    CONSTRAINT FK_OrderDetails_Orders FOREIGN KEY (OrderID) REFERENCES Orders(OrderID),
    CONSTRAINT FK_OrderDetails_Products FOREIGN KEY (ProductID) REFERENCES Products(ProductID),
    CONSTRAINT CK_OrderDetails_Quantity CHECK (Quantity > 0)
);
GO

/* ---------- DU LIEU MAU ---------- */

INSERT INTO Departments (DepartmentName) VALUES
(N'IT'), (N'Sales'), (N'HR'), (N'Finance');
GO

INSERT INTO Employees (FullName, Email, DepartmentID, ManagerID, HireDate, Salary) VALUES
(N'Nguyen Van An',    'an.nguyen@company.vn',    1, NULL, '2018-01-15', 35000000),
(N'Tran Thi Bich',    'bich.tran@company.vn',    1, 1,    '2019-03-01', 22000000),
(N'Le Van Cuong',     'cuong.le@company.vn',     1, 1,    '2020-06-10', 20000000),
(N'Pham Thi Dung',    'dung.pham@company.vn',    2, NULL, '2017-05-20', 32000000),
(N'Hoang Van Em',     'em.hoang@company.vn',     2, 4,    '2019-08-15', 18000000),
(N'Vu Thi Phuong',    'phuong.vu@company.vn',    2, 4,    '2021-02-01', 17000000),
(N'Dang Van Giang',   'giang.dang@company.vn',   3, NULL, '2016-11-01', 28000000),
(N'Bui Thi Ha',       'ha.bui@company.vn',       3, 7,    '2020-09-01', 15000000),
(N'Do Van Khanh',     'khanh.do@company.vn',     4, NULL, '2015-04-10', 30000000),
(N'Ngo Thi Lan',      'lan.ngo@company.vn',      4, 9,    '2021-07-01', 16000000);
GO

UPDATE Departments SET ManagerID = 1 WHERE DepartmentName = N'IT';
UPDATE Departments SET ManagerID = 4 WHERE DepartmentName = N'Sales';
UPDATE Departments SET ManagerID = 7 WHERE DepartmentName = N'HR';
UPDATE Departments SET ManagerID = 9 WHERE DepartmentName = N'Finance';
GO

INSERT INTO Customers (CustomerName, Email, City) VALUES
(N'Cong ty TNHH Minh Phat',  'contact@minhphat.vn',  N'Ha Noi'),
(N'Cong ty CP Dai Duong',    'info@daiduong.vn',     N'Da Nang'),
(N'Cua hang Thanh Tam',      'thanhtam@shop.vn',     N'Ho Chi Minh'),
(N'Sieu thi Gia Dinh Viet',  'giadinhviet@shop.vn',  N'Ha Noi'),
(N'Cong ty TNHH Song Hong',  'songhong@company.vn',  N'Hai Phong'),
(N'Cua hang Hoa Mai',        'hoamai@shop.vn',       N'Can Tho'),
(N'Cong ty CP Viet Tien',    'viettien@company.vn',  N'Ho Chi Minh'),
(N'Sieu thi An Khang',       'ankhang@shop.vn',      N'Da Nang');
GO

INSERT INTO Products (ProductName, Category, UnitPrice, StockQty) VALUES
(N'Laptop Dell Vostro',   N'Laptop',      15500000, 25),
(N'Laptop HP Pavilion',   N'Laptop',      13800000, 30),
(N'Man hinh Dell 24"',    N'Man hinh',     3200000, 50),
(N'Ban phim co Logitech', N'Phu kien',      950000, 100),
(N'Chuot khong day',      N'Phu kien',      350000, 150),
(N'May in Canon',         N'May in',       2800000, 20),
(N'Tai nghe Sony',        N'Phu kien',     1200000, 60),
(N'Ghe van phong',        N'Noi that',     2100000, 40),
(N'Ban lam viec',         N'Noi that',     3500000, 15),
(N'O cung SSD 1TB',       N'Linh kien',    1650000, 80);
GO

INSERT INTO Orders (CustomerID, EmployeeID, OrderDate, Status) VALUES
(1, 5, '2025-05-03', N'Completed'),
(2, 5, '2025-05-07', N'Completed'),
(3, 6, '2025-05-12', N'Completed'),
(1, 5, '2025-06-01', N'Completed'),
(4, 6, '2025-06-05', N'Completed'),
(5, 5, '2025-06-10', N'Cancelled'),
(2, 6, '2025-06-18', N'Completed'),
(6, 5, '2025-06-22', N'Completed'),
(3, 6, '2025-07-01', N'Completed'),
(7, 5, '2025-07-04', N'Completed'),
(1, 6, '2025-07-09', N'Completed'),
(8, 5, '2025-07-15', N'Pending'),
(4, 6, '2025-07-20', N'Completed'),
(5, 5, '2025-07-25', N'Completed'),
(2, 6, '2025-08-01', N'Completed'),
(6, 5, '2025-08-05', N'Completed'),
(7, 6, '2025-08-10', N'Pending'),
(3, 5, '2025-08-14', N'Completed'),
(8, 6, '2025-08-20', N'Completed'),
(1, 5, '2025-08-28', N'Completed');
GO

INSERT INTO OrderDetails (OrderID, ProductID, Quantity, UnitPrice) VALUES
(1, 1, 2, 15500000), (1, 4, 3, 950000),
(2, 3, 5, 3200000),
(3, 2, 1, 13800000), (3, 5, 4, 350000),
(4, 6, 2, 2800000),
(5, 7, 6, 1200000), (5, 8, 2, 2100000),
(6, 1, 1, 15500000),
(7, 9, 3, 3500000),
(8, 10, 4, 1650000), (8, 3, 2, 3200000),
(9, 4, 10, 950000),
(10, 1, 1, 15500000), (10, 2, 1, 13800000),
(11, 5, 8, 350000),
(12, 6, 1, 2800000),
(13, 7, 3, 1200000),
(14, 8, 4, 2100000),
(15, 9, 2, 3500000),
(16, 10, 6, 1650000),
(17, 1, 1, 15500000),
(18, 2, 2, 13800000),
(19, 3, 3, 3200000),
(20, 4, 5, 950000), (20, 5, 5, 350000);
GO

PRINT 'CompanyDB da duoc tao va nap du lieu mau thanh cong.';
