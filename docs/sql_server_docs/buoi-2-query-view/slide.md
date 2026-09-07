---
marp: true
paginate: true
---

# Buổi 2: Query nâng cao & View

### SQL Server cho Dev — Buổi 2/4 (180 phút)

Sau buổi học này bạn sẽ:

- Viết được JOIN đúng loại (INNER/LEFT/RIGHT/FULL/CROSS/SELF) cho từng tình huống nghiệp vụ
- Dùng được Subquery và CTE để chia nhỏ query phức tạp, biết khi nào dùng loại nào
- Tổng hợp dữ liệu bằng GROUP BY/HAVING và phân biệt rõ với WHERE
- Dùng Window Function (ROW_NUMBER, RANK, SUM OVER...) để xếp hạng và tính running total
- Tạo và sử dụng VIEW để đơn giản hóa query và ẩn dữ liệu nhạy cảm

---

# Agenda Buổi 2 (180 phút)

| # | Nội dung | Thời lượng |
|---|----------|-----------|
| 1 | Ôn nhanh SELECT/WHERE/ORDER BY + cú pháp T-SQL (TOP, OFFSET-FETCH) | 10p |
| 2 | JOIN các loại | 40p |
| 3 | Subquery & CTE | 30p |
| 4 | Aggregate & GROUP BY/HAVING | 20p |
| 5 | Window Functions | 30p |
| 6 | View | 30p |
| 7 | Tổng kết | 10p |
| | **Lab thực hành** (tách riêng) | 30p |

---

## Phần 1: Ôn nhanh SELECT/WHERE/ORDER BY

- Cú pháp cơ bản: bạn đã biết — không nhắc lại
- T-SQL dùng **TOP** thay vì `LIMIT` (MySQL/Postgres)
- T-SQL dùng **OFFSET ... FETCH NEXT** để phân trang

```sql
-- Lấy 5 nhân viên lương cao nhất
SELECT TOP 5 FullName, Salary
FROM Employees
ORDER BY Salary DESC;

-- Phân trang: bỏ qua 5 dòng đầu, lấy 5 dòng tiếp theo
SELECT EmployeeID, FullName, Salary
FROM Employees
ORDER BY Salary DESC
OFFSET 5 ROWS FETCH NEXT 5 ROWS ONLY;
```

---

## Phần 1: TOP với PERCENT và WITH TIES

- `TOP n PERCENT`: lấy % số dòng
- `WITH TIES`: lấy thêm các dòng bằng giá trị với dòng cuối cùng

```sql
-- Lấy 20% nhân viên lương cao nhất
SELECT TOP 20 PERCENT FullName, Salary
FROM Employees
ORDER BY Salary DESC;

-- Lấy top 3 nhưng giữ luôn các dòng đồng hạng
SELECT TOP 3 WITH TIES FullName, Salary
FROM Employees
ORDER BY Salary DESC;
```

> Lưu ý: `OFFSET/FETCH` bắt buộc phải có `ORDER BY` đi kèm.

---

# Phần 2: JOIN các loại (40 phút)

- INNER JOIN
- LEFT JOIN / RIGHT JOIN
- FULL JOIN
- CROSS JOIN
- SELF JOIN

Trực quan hóa bằng Venn diagram khi giảng trên lớp.

---

## INNER JOIN — chỉ lấy dòng khớp cả 2 bên

- Trả về dòng có match ở **cả hai** bảng
- Dùng nhiều nhất trong thực tế

```sql
-- Danh sách đơn hàng kèm tên khách hàng
SELECT o.OrderID, o.OrderDate, c.CustomerName, o.Status
FROM Orders o
INNER JOIN Customers c ON o.CustomerID = c.CustomerID;
```

- Nếu `CustomerID` trong `Orders` không tồn tại trong `Customers` → dòng đó bị loại

---

## LEFT JOIN — giữ toàn bộ bảng trái

- Giữ tất cả dòng bảng trái, dù không có match bên phải (NULL)
- Dùng để tìm "cái gì chưa có" (chưa từng đặt hàng, chưa có nhân viên...)

```sql
-- Liệt kê TẤT CẢ khách hàng, kể cả khách chưa từng đặt hàng
SELECT c.CustomerName, o.OrderID, o.OrderDate
FROM Customers c
LEFT JOIN Orders o ON c.CustomerID = o.CustomerID;

-- Tìm khách hàng CHƯA từng đặt hàng
SELECT c.CustomerName
FROM Customers c
LEFT JOIN Orders o ON c.CustomerID = o.CustomerID
WHERE o.OrderID IS NULL;
```

---

## RIGHT JOIN — giữ toàn bộ bảng phải

- Ngược lại LEFT JOIN: giữ toàn bộ dòng bảng bên phải
- Thực tế ít dùng vì luôn có thể viết lại bằng LEFT JOIN (đổi thứ tự bảng)

```sql
-- Tương đương ví dụ LEFT JOIN ở trên, chỉ đổi vị trí bảng
SELECT c.CustomerName, o.OrderID
FROM Orders o
RIGHT JOIN Customers c ON o.CustomerID = c.CustomerID;
```

> Khuyến nghị: dùng LEFT JOIN cho dễ đọc, tránh RIGHT JOIN trừ khi có lý do đặc biệt

---

## FULL JOIN — giữ cả hai bên, khớp thì ghép

- Trả về toàn bộ dòng của **cả hai** bảng
- Dòng không khớp bên nào thì cột bên đó là NULL

```sql
-- Đối chiếu toàn bộ nhân viên và đơn hàng họ xử lý
-- (kể cả nhân viên chưa xử lý đơn nào - giả định, minh họa cú pháp)
SELECT e.FullName, o.OrderID
FROM Employees e
FULL JOIN Orders o ON e.EmployeeID = o.EmployeeID;
```

- Dùng khi cần thấy đầy đủ chênh lệch dữ liệu giữa 2 bảng (đối chiếu, audit)

---

## CROSS JOIN — tích Descartes

- Ghép **mọi dòng** bảng A với **mọi dòng** bảng B (không cần điều kiện ON)
- Số dòng kết quả = số dòng A × số dòng B
- Cẩn thận: dễ tạo ra lượng dữ liệu khổng lồ

```sql
-- Sinh ma trận Category x City để phân tích "ô trống" thị trường
SELECT DISTINCT p.Category, c.City
FROM Products p
CROSS JOIN Customers c;
```

- Ứng dụng thực tế: sinh lịch, sinh tổ hợp cấu hình, dữ liệu test

---

## SELF JOIN — bảng tự join với chính nó

- Dùng khi bảng có quan hệ với chính nó (VD: `Employees.ManagerID -> Employees.EmployeeID`)
- Coi như 2 bảng khác nhau bằng alias

```sql
-- Danh sách nhân viên kèm tên quản lý trực tiếp
SELECT
    nv.FullName  AS NhanVien,
    ql.FullName  AS QuanLy
FROM Employees nv
LEFT JOIN Employees ql ON nv.ManagerID = ql.EmployeeID;
```

- LEFT JOIN vì có nhân viên không có quản lý (ManagerID NULL — giám đốc)

---

## JOIN nhiều bảng — ví dụ tổng hợp

- Thực tế thường JOIN 3-4 bảng trở lên trong 1 query

```sql
-- Chi tiết đơn hàng: khách hàng, nhân viên, sản phẩm
SELECT
    o.OrderID,
    c.CustomerName,
    e.FullName   AS NhanVienBanHang,
    p.ProductName,
    od.Quantity,
    od.UnitPrice,
    od.Quantity * od.UnitPrice AS ThanhTien
FROM Orders o
INNER JOIN Customers c    ON o.CustomerID = c.CustomerID
INNER JOIN Employees e    ON o.EmployeeID = e.EmployeeID
INNER JOIN OrderDetails od ON o.OrderID = od.OrderID
INNER JOIN Products p     ON od.ProductID = p.ProductID
WHERE o.Status = 'Completed';
```

---

# Phần 3: Subquery & CTE (30 phút)

- Subquery trong WHERE
- Subquery trong SELECT
- Subquery trong FROM (derived table)
- CTE (Common Table Expression) — `WITH`
- So sánh CTE vs Subquery vs Temp table/Table variable

---

## Subquery trong WHERE

- Dùng kết quả 1 query con để lọc query ngoài

```sql
-- Nhân viên có lương cao hơn lương trung bình toàn công ty
SELECT FullName, Salary
FROM Employees
WHERE Salary > (SELECT AVG(Salary) FROM Employees);

-- Khách hàng đã từng đặt đơn "Completed"
SELECT CustomerName
FROM Customers
WHERE CustomerID IN (
    SELECT CustomerID FROM Orders WHERE Status = 'Completed'
);
```

---

## Subquery tương quan (correlated) & EXISTS

- Subquery tham chiếu đến bảng ngoài → chạy lại cho mỗi dòng
- `EXISTS` thường nhanh hơn `IN` khi chỉ cần kiểm tra "có tồn tại hay không"

```sql
-- Khách hàng có ít nhất 1 đơn hàng
SELECT c.CustomerName
FROM Customers c
WHERE EXISTS (
    SELECT 1 FROM Orders o WHERE o.CustomerID = c.CustomerID
);

-- Nhân viên có lương cao nhất TRONG phòng ban của mình
SELECT e1.FullName, e1.Salary, e1.DepartmentID
FROM Employees e1
WHERE Salary = (
    SELECT MAX(e2.Salary)
    FROM Employees e2
    WHERE e2.DepartmentID = e1.DepartmentID
);
```

---

## Subquery trong SELECT và FROM

- Trong SELECT: trả về 1 giá trị vô hướng (scalar) cho mỗi dòng
- Trong FROM: dùng như 1 bảng tạm (derived table), bắt buộc có alias

```sql
-- Subquery trong SELECT: số đơn hàng của mỗi khách hàng
SELECT
    c.CustomerName,
    (SELECT COUNT(*) FROM Orders o WHERE o.CustomerID = c.CustomerID) AS SoDonHang
FROM Customers c;

-- Subquery trong FROM: doanh thu theo khách hàng, chỉ lấy > 20 triệu
SELECT dt.CustomerID, dt.TongDoanhThu
FROM (
    SELECT o.CustomerID, SUM(od.Quantity * od.UnitPrice) AS TongDoanhThu
    FROM Orders o
    JOIN OrderDetails od ON o.OrderID = od.OrderID
    GROUP BY o.CustomerID
) AS dt
WHERE dt.TongDoanhThu > 20000000;
```

---

## CTE — Common Table Expression

- Cú pháp `WITH ten_cte AS (...)` — đặt trước câu SELECT chính
- Giúp query dài dễ đọc hơn nhiều so với subquery lồng nhau
- Chỉ tồn tại trong phạm vi 1 câu lệnh

```sql
WITH DoanhThuKhachHang AS (
    SELECT o.CustomerID, SUM(od.Quantity * od.UnitPrice) AS TongDoanhThu
    FROM Orders o
    JOIN OrderDetails od ON o.OrderID = od.OrderID
    WHERE o.Status = 'Completed'
    GROUP BY o.CustomerID
)
SELECT c.CustomerName, dtk.TongDoanhThu
FROM DoanhThuKhachHang dtk
JOIN Customers c ON c.CustomerID = dtk.CustomerID
ORDER BY dtk.TongDoanhThu DESC;
```

---

## CTE đệ quy (recursive) — giới thiệu

- Dùng để duyệt cây phân cấp (VD: sơ đồ tổ chức nhân viên - quản lý)

```sql
WITH SoDoToChuc AS (
    -- Anchor: nhân viên cấp cao nhất (không có quản lý)
    SELECT EmployeeID, FullName, ManagerID, 0 AS Cap
    FROM Employees
    WHERE ManagerID IS NULL

    UNION ALL

    -- Recursive: nối tiếp xuống cấp dưới
    SELECT e.EmployeeID, e.FullName, e.ManagerID, sdt.Cap + 1
    FROM Employees e
    JOIN SoDoToChuc sdt ON e.ManagerID = sdt.EmployeeID
)
SELECT * FROM SoDoToChuc ORDER BY Cap;
```

---

## So sánh: CTE vs Subquery vs Temp Table / Table Variable

| Tiêu chí | Subquery | CTE | Temp Table (`#t`) | Table Variable (`@t`) |
|---|---|---|---|---|
| Tái sử dụng nhiều lần trong query | Không (phải viết lại) | Có (gọi tên nhiều lần) | Có | Có |
| Đệ quy | Không | Có | Không | Không |
| Có thể đánh index riêng | Không | Không | Có | Hạn chế |
| Phạm vi tồn tại | 1 câu lệnh | 1 câu lệnh | Cả session/batch | Cả batch |
| Phù hợp dữ liệu lớn, dùng lại nhiều lần | Kém | Trung bình | Tốt | Trung bình |

- Quy tắc chọn nhanh: query đơn giản → subquery; query dễ đọc, dùng lại tên → CTE; dữ liệu lớn cần index/dùng nhiều bước → temp table

---

# Phần 4: Aggregate & GROUP BY/HAVING (20 phút)

- Hàm tổng hợp: `SUM`, `COUNT`, `AVG`, `MIN`, `MAX`
- `GROUP BY` theo 1 hoặc nhiều cột
- `HAVING` lọc SAU khi gom nhóm — khác `WHERE`

---

## Hàm Aggregate cơ bản

```sql
-- Tổng quan đơn hàng
SELECT
    COUNT(*)              AS SoDonHang,
    SUM(od.Quantity)      AS TongSoLuong,
    AVG(od.UnitPrice)     AS DonGiaTrungBinh,
    MIN(od.UnitPrice)     AS DonGiaThapNhat,
    MAX(od.UnitPrice)     AS DonGiaCaoNhat
FROM OrderDetails od;
```

- `COUNT(*)`: đếm tất cả dòng, kể cả NULL
- `COUNT(cột)`: chỉ đếm dòng có giá trị khác NULL ở cột đó

---

## GROUP BY nhiều cột

```sql
-- Doanh thu theo từng nhân viên, theo từng tháng
SELECT
    e.FullName,
    YEAR(o.OrderDate)  AS Nam,
    MONTH(o.OrderDate) AS Thang,
    SUM(od.Quantity * od.UnitPrice) AS DoanhThu
FROM Orders o
JOIN Employees e     ON o.EmployeeID = e.EmployeeID
JOIN OrderDetails od ON o.OrderID = od.OrderID
WHERE o.Status = 'Completed'
GROUP BY e.FullName, YEAR(o.OrderDate), MONTH(o.OrderDate)
ORDER BY Nam, Thang, DoanhThu DESC;
```

- Mọi cột trong SELECT không nằm trong hàm aggregate → **bắt buộc** có mặt trong GROUP BY

---

## HAVING vs WHERE

- `WHERE`: lọc **dòng dữ liệu gốc**, chạy **trước** khi gom nhóm
- `HAVING`: lọc **nhóm kết quả** sau `GROUP BY`, chạy **sau** khi gom nhóm

```sql
-- Khách hàng có tổng doanh thu > 20 triệu (chỉ tính đơn Completed)
SELECT
    o.CustomerID,
    SUM(od.Quantity * od.UnitPrice) AS TongDoanhThu
FROM Orders o
JOIN OrderDetails od ON o.OrderID = od.OrderID
WHERE o.Status = 'Completed'          -- lọc trước khi gom nhóm
GROUP BY o.CustomerID
HAVING SUM(od.Quantity * od.UnitPrice) > 20000000;  -- lọc sau khi gom nhóm
```

> Sai lầm thường gặp: đặt điều kiện tổng hợp (SUM/COUNT...) vào WHERE → SQL Server báo lỗi

---

# Phần 5: Window Functions (30 phút)

- `ROW_NUMBER()`, `RANK()`, `DENSE_RANK()`
- `PARTITION BY` — chia nhóm để tính riêng
- `SUM() OVER` — running total
- Khác biệt cốt lõi với GROUP BY: **không làm gộp dòng**, vẫn giữ nguyên số dòng

---

## Cú pháp chung: OVER (PARTITION BY ... ORDER BY ...)

```sql
SELECT
    EmployeeID,
    DepartmentID,
    Salary,
    ROW_NUMBER() OVER (PARTITION BY DepartmentID ORDER BY Salary DESC) AS ThuTu
FROM Employees;
```

- `PARTITION BY`: chia dữ liệu thành từng nhóm (giống GROUP BY nhưng không gộp dòng)
- `ORDER BY` trong `OVER`: thứ tự tính toán trong từng nhóm
- Không có `PARTITION BY` → coi toàn bộ bảng là 1 nhóm duy nhất

---

## ROW_NUMBER() — đánh số thứ tự duy nhất

- Luôn cho số thứ tự **không trùng nhau**, kể cả khi giá trị bằng nhau

```sql
-- Đánh số nhân viên trong mỗi phòng ban theo lương giảm dần
SELECT
    FullName, DepartmentID, Salary,
    ROW_NUMBER() OVER (PARTITION BY DepartmentID ORDER BY Salary DESC) AS STT
FROM Employees;
```

- Ứng dụng phổ biến nhất: **lấy top-N mỗi nhóm** (xem slide sau)

---

## RANK() vs DENSE_RANK() — xử lý đồng hạng

- `RANK()`: đồng hạng thì cùng số, số tiếp theo **bị nhảy cách**
- `DENSE_RANK()`: đồng hạng thì cùng số, số tiếp theo **không nhảy cách**

```sql
SELECT
    FullName, Salary,
    RANK()       OVER (ORDER BY Salary DESC) AS Rank_,
    DENSE_RANK() OVER (ORDER BY Salary DESC) AS DenseRank_,
    ROW_NUMBER() OVER (ORDER BY Salary DESC) AS RowNum_
FROM Employees;
```

- Ví dụ: 2 người cùng hạng 2 → RANK cho ra 2, 2, **4** (bỏ qua 3) | DENSE_RANK cho ra 2, 2, **3**

---

## Ứng dụng: Top-N theo mỗi nhóm

- Bài toán kinh điển: "Top 3 nhân viên doanh số cao nhất **mỗi phòng ban**"
- Dùng CTE + ROW_NUMBER (không dùng TOP vì TOP không tự chia nhóm được)

```sql
WITH XepHang AS (
    SELECT
        e.FullName,
        e.DepartmentID,
        SUM(od.Quantity * od.UnitPrice) AS DoanhSo,
        ROW_NUMBER() OVER (
            PARTITION BY e.DepartmentID
            ORDER BY SUM(od.Quantity * od.UnitPrice) DESC
        ) AS ThuHang
    FROM Employees e
    JOIN Orders o        ON o.EmployeeID = e.EmployeeID
    JOIN OrderDetails od ON od.OrderID = o.OrderID
    WHERE o.Status = 'Completed'
    GROUP BY e.FullName, e.DepartmentID
)
SELECT * FROM XepHang WHERE ThuHang <= 3;
```

---

## SUM() OVER — Running Total (tổng lũy kế)

- Thêm `ORDER BY` vào `OVER` mà không `PARTITION BY` theo nhóm cần cộng dồn riêng

```sql
-- Doanh thu lũy kế theo từng ngày có đơn hàng
SELECT
    o.OrderDate,
    SUM(od.Quantity * od.UnitPrice) AS DoanhThuNgay,
    SUM(SUM(od.Quantity * od.UnitPrice)) OVER (
        ORDER BY o.OrderDate
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS DoanhThuLuyKe
FROM Orders o
JOIN OrderDetails od ON o.OrderID = od.OrderID
WHERE o.Status = 'Completed'
GROUP BY o.OrderDate
ORDER BY o.OrderDate;
```

- `ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW`: cộng dồn từ dòng đầu đến dòng hiện tại

---

## Running Total theo từng nhóm (PARTITION BY)

```sql
-- Doanh thu lũy kế theo từng khách hàng, theo thời gian
SELECT
    o.CustomerID,
    o.OrderDate,
    SUM(od.Quantity * od.UnitPrice) OVER (
        PARTITION BY o.CustomerID
        ORDER BY o.OrderDate
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS LuyKeTheoKhachHang
FROM Orders o
JOIN OrderDetails od ON o.OrderID = od.OrderID
WHERE o.Status = 'Completed'
ORDER BY o.CustomerID, o.OrderDate;
```

- Mỗi khách hàng có 1 chuỗi lũy kế riêng, độc lập với khách khác

---

## Window Function khác: LAG/LEAD (giới thiệu nhanh)

- `LAG()`: lấy giá trị dòng **trước đó**
- `LEAD()`: lấy giá trị dòng **kế tiếp**
- Dùng để so sánh kỳ này với kỳ trước (tăng/giảm)

```sql
SELECT
    o.OrderDate,
    SUM(od.Quantity * od.UnitPrice) AS DoanhThuNgay,
    LAG(SUM(od.Quantity * od.UnitPrice)) OVER (ORDER BY o.OrderDate) AS DoanhThuNgayTruoc
FROM Orders o
JOIN OrderDetails od ON o.OrderID = od.OrderID
WHERE o.Status = 'Completed'
GROUP BY o.OrderDate
ORDER BY o.OrderDate;
```

---

# Phần 6: View (30 phút)

- `CREATE VIEW` — cú pháp cơ bản
- Mục đích: đơn giản hóa query phức tạp, bảo mật (ẩn cột nhạy cảm)
- Updatable view và giới hạn
- `WITH CHECK OPTION`
- Indexed View (giới thiệu sơ lược)

---

## CREATE VIEW — cú pháp cơ bản

- View là 1 "câu SELECT được đặt tên", không lưu dữ liệu riêng (trừ Indexed View)

```sql
CREATE VIEW vw_ChiTietDonHang AS
SELECT
    o.OrderID,
    c.CustomerName,
    e.FullName AS NhanVien,
    p.ProductName,
    od.Quantity,
    od.UnitPrice,
    od.Quantity * od.UnitPrice AS ThanhTien
FROM Orders o
JOIN Customers c     ON o.CustomerID = c.CustomerID
JOIN Employees e     ON o.EmployeeID = e.EmployeeID
JOIN OrderDetails od ON o.OrderID = od.OrderID
JOIN Products p      ON od.ProductID = p.ProductID;
GO

-- Dùng như 1 bảng bình thường
SELECT * FROM vw_ChiTietDonHang WHERE CustomerName LIKE N'%Minh Phat%';
```

---

## Mục đích 1: Đơn giản hóa query phức tạp

- Ẩn JOIN nhiều bảng, logic phức tạp sau 1 cái tên dễ hiểu
- Người dùng cuối (BI, report) chỉ cần `SELECT * FROM vw_...`

```sql
CREATE VIEW vw_DoanhThuTheoKhachHang AS
SELECT
    c.CustomerID,
    c.CustomerName,
    SUM(od.Quantity * od.UnitPrice) AS TongDoanhThu,
    COUNT(DISTINCT o.OrderID) AS SoDonHang
FROM Customers c
JOIN Orders o        ON c.CustomerID = o.CustomerID
JOIN OrderDetails od ON o.OrderID = od.OrderID
WHERE o.Status = 'Completed'
GROUP BY c.CustomerID, c.CustomerName;
GO
```

---

## Mục đích 2: Bảo mật — ẩn cột nhạy cảm

- Cấp quyền `SELECT` trên VIEW thay vì trên bảng gốc → ẩn cột như `Salary`, `Email`

```sql
CREATE VIEW vw_DanhSachNhanVien AS
SELECT
    EmployeeID,
    FullName,
    DepartmentID,
    HireDate
    -- KHÔNG có Salary, Email
FROM Employees;
GO

-- Cấp quyền cho user chỉ được xem qua view này
GRANT SELECT ON vw_DanhSachNhanVien TO SomeReadOnlyUser;
```

- Nhân viên phòng nhân sự cấp thấp có thể xem danh sách mà không thấy lương

---

## Updatable View và giới hạn

- View trên **1 bảng, không aggregate, không DISTINCT** → có thể `INSERT/UPDATE/DELETE` qua view

```sql
CREATE VIEW vw_NhanVienIT AS
SELECT EmployeeID, FullName, Email, Salary
FROM Employees
WHERE DepartmentID = 1;
GO

-- Cập nhật qua view — hợp lệ vì view đơn giản, 1 bảng
UPDATE vw_NhanVienIT SET Salary = Salary * 1.1 WHERE EmployeeID = 2;
```

- **KHÔNG update được** nếu view có: JOIN nhiều bảng, `GROUP BY`, `DISTINCT`, hàm aggregate, `TOP`... (SQL Server sẽ báo lỗi)

---

## WITH CHECK OPTION

- Ngăn việc `INSERT/UPDATE` qua view tạo ra dòng **không còn thỏa điều kiện WHERE của view**

```sql
CREATE VIEW vw_NhanVienIT AS
SELECT EmployeeID, FullName, Email, Salary, DepartmentID
FROM Employees
WHERE DepartmentID = 1
WITH CHECK OPTION;
GO

-- Lỗi! Không được đổi DepartmentID vì sẽ làm dòng "biến mất" khỏi view
UPDATE vw_NhanVienIT SET DepartmentID = 2 WHERE EmployeeID = 2;
```

- Không có `WITH CHECK OPTION`: lệnh trên vẫn chạy được, dòng chỉ "biến mất" khỏi view sau khi update

---

## Indexed View (giới thiệu sơ lược)

- View bình thường: **không lưu dữ liệu**, chạy lại query mỗi lần gọi
- Indexed View: tạo **clustered index** trên view → dữ liệu được **vật lý hóa** (materialized), lưu thật trên đĩa

```sql
CREATE VIEW vw_DoanhThuTheoKhachHang_Indexed
WITH SCHEMABINDING AS
SELECT
    o.CustomerID,
    SUM(od.Quantity * od.UnitPrice) AS TongDoanhThu,
    COUNT_BIG(*) AS SoDong
FROM dbo.Orders o
JOIN dbo.OrderDetails od ON o.OrderID = od.OrderID
GROUP BY o.CustomerID;
GO

CREATE UNIQUE CLUSTERED INDEX IX_vw_DoanhThu ON vw_DoanhThuTheoKhachHang_Indexed(CustomerID);
```

- Yêu cầu: `WITH SCHEMABINDING`, `COUNT_BIG` thay `COUNT`, không dùng được với query linh hoạt
- Đánh đổi: đọc nhanh hơn nhiều, nhưng ghi (INSERT/UPDATE/DELETE) vào bảng gốc chậm hơn → chỉ dùng khi thật cần thiết, sẽ không đi sâu ở khóa này

---

# Tổng kết Buổi 2

- **JOIN**: INNER (khớp cả 2), LEFT/RIGHT (giữ 1 bên), FULL (giữ cả 2), CROSS (tích Descartes), SELF (bảng tự join)
- **Subquery & CTE**: CTE giúp query dễ đọc hơn, dùng lại tên được, hỗ trợ đệ quy
- **GROUP BY/HAVING**: WHERE lọc trước gộp nhóm, HAVING lọc sau gộp nhóm
- **Window Function**: `PARTITION BY` chia nhóm mà không gộp dòng — chìa khóa cho xếp hạng & running total
- **View**: đơn giản hóa query + bảo mật cột nhạy cảm; nhớ giới hạn của updatable view

### Preview Buổi 3: Stored Procedure, Function, Trigger

- Đóng gói logic nghiệp vụ vào Stored Procedure có tham số
- Viết Scalar Function & Table-Valued Function tái sử dụng trong SELECT
- Trigger tự động phản ứng khi dữ liệu thay đổi (INSERT/UPDATE/DELETE)
