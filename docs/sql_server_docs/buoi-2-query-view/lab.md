# Lab Buổi 2: Query nâng cao & View

**Mục tiêu:** Thực hành JOIN các loại, Subquery/CTE, Aggregate/GROUP BY, Window Function và tạo VIEW trên bộ dữ liệu `CompanyDB`, để củng cố các khái niệm đã học trong buổi 2.

> Toàn bộ bài tập dùng chung database `CompanyDB` đã được nạp sẵn từ file `00-du-lieu-mau/setup.sql`. Chạy `USE CompanyDB;` trước khi làm bài.

---

## ĐỀ BÀI

**Bài 1 (INNER JOIN).**
Liệt kê danh sách đơn hàng gồm: mã đơn hàng, tên khách hàng, tên nhân viên bán hàng, ngày đặt hàng và trạng thái. Chỉ lấy các đơn có trạng thái `'Completed'`.

**Bài 2 (LEFT JOIN).**
Liệt kê tất cả khách hàng trong hệ thống kèm tổng số đơn hàng họ đã đặt (kể cả khách hàng chưa từng đặt đơn nào thì số đơn hàng = 0).

**Bài 3 (SELF JOIN).**
Liệt kê danh sách nhân viên kèm tên người quản lý trực tiếp của họ. Với nhân viên không có quản lý (giám đốc), cột tên quản lý hiển thị `'(Không có quản lý)'`.

**Bài 4 (JOIN nhiều bảng).**
Liệt kê chi tiết từng dòng sản phẩm đã bán: mã đơn hàng, tên khách hàng, tên sản phẩm, số lượng, đơn giá, thành tiền (số lượng × đơn giá). Chỉ lấy đơn `'Completed'`, sắp xếp theo thành tiền giảm dần.

**Bài 5 (Subquery + Aggregate).**
Tìm những nhân viên có lương cao hơn lương trung bình của **toàn bộ phòng ban mà họ trực thuộc** (không phải trung bình toàn công ty).

**Bài 6 (CTE).**
Dùng CTE để tính doanh thu (theo đơn `'Completed'`) của từng khách hàng, sau đó chỉ hiển thị những khách hàng có doanh thu nằm trong **top 3 cao nhất**.

**Bài 7 (GROUP BY nhiều cột + HAVING).**
Tính doanh thu theo từng nhân viên, theo từng tháng (dựa trên `OrderDate`, chỉ tính đơn `'Completed'`). Chỉ hiển thị các dòng có doanh thu tháng đó lớn hơn 30 triệu.

**Bài 8 (Window Function — xếp hạng theo nhóm).**
Xếp hạng nhân viên theo tổng doanh số bán hàng (đơn `'Completed'`) **trong từng tháng**, sau đó chỉ lấy **top 3 nhân viên có doanh số cao nhất mỗi tháng**. Dùng `ROW_NUMBER()` với `PARTITION BY`.

**Bài 9 (Window Function — running total).**
Tính doanh thu lũy kế (running total) theo từng ngày có phát sinh đơn hàng `'Completed'`, sắp xếp theo `OrderDate` tăng dần.

**Bài 10 (View).**
Tạo view `vw_BaoCaoDoanhThuKhachHang` hiển thị báo cáo doanh thu theo khách hàng gồm: mã khách hàng, tên khách hàng, thành phố, tổng số đơn hàng `'Completed'`, tổng doanh thu. Sau đó viết 1 câu SELECT từ view này để lấy các khách hàng có doanh thu trên 20 triệu, sắp xếp giảm dần theo doanh thu.

---

## ĐÁP ÁN

### Bài 1

```sql
SELECT
    o.OrderID,
    c.CustomerName,
    e.FullName AS NhanVienBanHang,
    o.OrderDate,
    o.Status
FROM Orders o
INNER JOIN Customers c ON o.CustomerID = c.CustomerID
INNER JOIN Employees e ON o.EmployeeID = e.EmployeeID
WHERE o.Status = 'Completed';
```

*Giải thích:* dùng `INNER JOIN` vì mọi đơn hàng luôn phải có khách hàng và nhân viên hợp lệ (ràng buộc FK NOT NULL) — không có trường hợp thiếu match nên INNER JOIN là đủ.

---

### Bài 2

```sql
SELECT
    c.CustomerID,
    c.CustomerName,
    COUNT(o.OrderID) AS SoDonHang
FROM Customers c
LEFT JOIN Orders o ON c.CustomerID = o.CustomerID
GROUP BY c.CustomerID, c.CustomerName;
```

*Giải thích:* `LEFT JOIN` giữ lại toàn bộ khách hàng kể cả khi không có đơn hàng nào khớp (`o.OrderID` sẽ là `NULL`). `COUNT(o.OrderID)` đếm cột từ bảng `Orders` nên tự động cho ra `0` với khách hàng không có đơn (vì `COUNT` bỏ qua `NULL`), khác với `COUNT(*)` sẽ luôn đếm ra ít nhất 1.

---

### Bài 3

```sql
SELECT
    nv.FullName AS NhanVien,
    ISNULL(ql.FullName, N'(Không có quản lý)') AS QuanLy
FROM Employees nv
LEFT JOIN Employees ql ON nv.ManagerID = ql.EmployeeID;
```

*Giải thích:* SELF JOIN — coi bảng `Employees` như 2 bảng độc lập bằng 2 alias (`nv` = nhân viên, `ql` = quản lý). `LEFT JOIN` vì có nhân viên có `ManagerID IS NULL` (giám đốc). `ISNULL()` thay thế giá trị NULL bằng chuỗi hiển thị mong muốn.

---

### Bài 4

```sql
SELECT
    o.OrderID,
    c.CustomerName,
    p.ProductName,
    od.Quantity,
    od.UnitPrice,
    od.Quantity * od.UnitPrice AS ThanhTien
FROM Orders o
INNER JOIN Customers c     ON o.CustomerID = c.CustomerID
INNER JOIN OrderDetails od ON o.OrderID = od.OrderID
INNER JOIN Products p      ON od.ProductID = p.ProductID
WHERE o.Status = 'Completed'
ORDER BY ThanhTien DESC;
```

*Giải thích:* JOIN 4 bảng theo đúng chuỗi khóa ngoại `Orders -> Customers`, `Orders -> OrderDetails -> Products`. Tất cả đều INNER JOIN vì các FK đều bắt buộc (NOT NULL).

---

### Bài 5

```sql
SELECT
    e1.FullName,
    e1.DepartmentID,
    e1.Salary
FROM Employees e1
WHERE e1.Salary > (
    SELECT AVG(e2.Salary)
    FROM Employees e2
    WHERE e2.DepartmentID = e1.DepartmentID
);
```

*Giải thích:* đây là subquery tương quan (correlated subquery) — subquery bên trong tham chiếu đến `e1.DepartmentID` của dòng đang xét ở query ngoài, nên nó chạy lại (tính `AVG`) riêng cho từng phòng ban chứ không phải trung bình toàn công ty.

---

### Bài 6

```sql
WITH DoanhThuKhachHang AS (
    SELECT
        o.CustomerID,
        SUM(od.Quantity * od.UnitPrice) AS TongDoanhThu
    FROM Orders o
    JOIN OrderDetails od ON o.OrderID = od.OrderID
    WHERE o.Status = 'Completed'
    GROUP BY o.CustomerID
)
SELECT TOP 3
    c.CustomerName,
    dtk.TongDoanhThu
FROM DoanhThuKhachHang dtk
JOIN Customers c ON c.CustomerID = dtk.CustomerID
ORDER BY dtk.TongDoanhThu DESC;
```

*Giải thích:* CTE `DoanhThuKhachHang` tính tổng doanh thu từng khách hàng trước, câu SELECT chính chỉ việc JOIN với `Customers` và lấy `TOP 3` theo doanh thu giảm dần — tách bước tính toán ra khỏi bước lọc/hiển thị giúp query dễ đọc hơn nhiều so với subquery lồng.

---

### Bài 7

```sql
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
HAVING SUM(od.Quantity * od.UnitPrice) > 30000000
ORDER BY Nam, Thang, DoanhThu DESC;
```

*Giải thích:* `WHERE o.Status = 'Completed'` lọc dòng dữ liệu gốc **trước khi** gom nhóm. `HAVING SUM(...) > 30000000` lọc kết quả **sau khi** đã `SUM` theo từng nhóm (nhân viên + năm + tháng) — không thể viết điều kiện này trong `WHERE` vì `SUM()` chưa tồn tại ở bước đó.

---

### Bài 8

```sql
WITH DoanhSoThang AS (
    SELECT
        e.FullName,
        YEAR(o.OrderDate)  AS Nam,
        MONTH(o.OrderDate) AS Thang,
        SUM(od.Quantity * od.UnitPrice) AS DoanhSo
    FROM Orders o
    JOIN Employees e     ON o.EmployeeID = e.EmployeeID
    JOIN OrderDetails od ON o.OrderID = od.OrderID
    WHERE o.Status = 'Completed'
    GROUP BY e.FullName, YEAR(o.OrderDate), MONTH(o.OrderDate)
),
XepHang AS (
    SELECT
        *,
        ROW_NUMBER() OVER (
            PARTITION BY Nam, Thang
            ORDER BY DoanhSo DESC
        ) AS ThuHang
    FROM DoanhSoThang
)
SELECT FullName, Nam, Thang, DoanhSo, ThuHang
FROM XepHang
WHERE ThuHang <= 3
ORDER BY Nam, Thang, ThuHang;
```

*Giải thích:* CTE đầu tính doanh số từng nhân viên theo từng tháng. CTE thứ hai dùng `ROW_NUMBER()` với `PARTITION BY Nam, Thang` để đánh số thứ tự **riêng cho từng tháng** (không bị lẫn giữa các tháng), sắp theo doanh số giảm dần. Câu SELECT cuối lọc `ThuHang <= 3` để lấy top 3 mỗi tháng.

---

### Bài 9

```sql
WITH DoanhThuNgay AS (
    SELECT
        o.OrderDate,
        SUM(od.Quantity * od.UnitPrice) AS DoanhThu
    FROM Orders o
    JOIN OrderDetails od ON o.OrderID = od.OrderID
    WHERE o.Status = 'Completed'
    GROUP BY o.OrderDate
)
SELECT
    OrderDate,
    DoanhThu,
    SUM(DoanhThu) OVER (
        ORDER BY OrderDate
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS DoanhThuLuyKe
FROM DoanhThuNgay
ORDER BY OrderDate;
```

*Giải thích:* CTE gom doanh thu theo từng ngày trước (tránh phải lồng `SUM(SUM(...))`). Sau đó `SUM(DoanhThu) OVER (ORDER BY OrderDate ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)` cộng dồn từ ngày đầu tiên đến ngày hiện tại của mỗi dòng — đây chính là running total. Vì không có `PARTITION BY`, toàn bộ bảng được coi là 1 nhóm duy nhất.

---

### Bài 10

```sql
CREATE VIEW vw_BaoCaoDoanhThuKhachHang AS
SELECT
    c.CustomerID,
    c.CustomerName,
    c.City,
    COUNT(DISTINCT o.OrderID) AS SoDonHang,
    SUM(od.Quantity * od.UnitPrice) AS TongDoanhThu
FROM Customers c
JOIN Orders o        ON c.CustomerID = o.CustomerID
JOIN OrderDetails od ON o.OrderID = od.OrderID
WHERE o.Status = 'Completed'
GROUP BY c.CustomerID, c.CustomerName, c.City;
GO

-- Sử dụng view
SELECT *
FROM vw_BaoCaoDoanhThuKhachHang
WHERE TongDoanhThu > 20000000
ORDER BY TongDoanhThu DESC;
```

*Giải thích:* view đóng gói toàn bộ logic JOIN 3 bảng + GROUP BY + aggregate vào 1 cái tên duy nhất. Người dùng sau này chỉ cần `SELECT ... FROM vw_BaoCaoDoanhThuKhachHang` mà không cần biết chi tiết JOIN bên trong. Lưu ý: vì view này có `GROUP BY` và hàm aggregate nên đây **không phải** updatable view — chỉ dùng để đọc (SELECT), không thể `INSERT/UPDATE/DELETE` qua view này.
