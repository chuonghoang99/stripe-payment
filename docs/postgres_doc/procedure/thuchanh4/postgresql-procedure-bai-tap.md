# Thực hành 4 — PostgreSQL PROCEDURE đặt chỗ và hủy chỗ

Viết hai procedure xử lý vòng đời của một phiên đặt chỗ trên hai bảng `sessions` và `bookings` đã có sẵn:

1. `sp_book_session`: đặt chỗ và trừ số chỗ còn trống.
2. `sp_cancel_booking`: hủy chỗ và hoàn lại đúng một lần.

Phần cần tự viết chỉ là hai procedure; không cần tạo thêm bảng.

Bài này **cùng mức độ với thực hành 1** (dành cho người mới) nhưng áp dụng trên một bài toán khác, và bổ sung thêm hai kỹ thuật:

- `PERFORM` để gọi một truy vấn mà không cần lấy kết quả.
- Lấy giá trị sau một `UPDATE` bằng `UPDATE ... RETURNING ... INTO`.

Các kỹ năng chính vẫn giữ nguyên như thực hành 1:

- Khai báo input parameter và biến cục bộ.
- Gán dữ liệu bằng `SELECT ... INTO`.
- Kiểm tra điều kiện bằng `IF` và `FOUND`.
- Chống race condition bằng row lock khi hai user đặt cùng một phiên.
- Cập nhật hai bảng trong một transaction atomic.
- Lấy ID vừa insert bằng `INSERT ... RETURNING ... INTO`.
- Báo lỗi bằng `RAISE EXCEPTION` và in kết quả bằng `RAISE NOTICE`.

Không cần dùng dynamic SQL, temp table, cursor hoặc exception handler.

---

## 1. Khởi động PostgreSQL

Project đã có [compose.yaml](../../../../compose.yaml). Chạy từ thư mục gốc project:

```bash
docker compose up -d postgres
docker compose ps postgres
docker compose exec postgres psql -U postgres -d postgres
```

Chờ container `postgres-lab` có trạng thái `healthy` rồi mới kết nối.

---

## 2. Tạo bảng và dữ liệu mẫu

Chạy toàn bộ block sau trong `psql`. Script chỉ xóa schema `procedure_lab_4`.

```sql
DROP SCHEMA IF EXISTS procedure_lab_4 CASCADE;
CREATE SCHEMA procedure_lab_4;

CREATE TABLE procedure_lab_4.sessions (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title          text NOT NULL,
    tutor          text NOT NULL DEFAULT 'TBA',
    capacity       integer NOT NULL CHECK (capacity > 0),
    remaining      integer NOT NULL CHECK (remaining >= 0),
    is_open        boolean NOT NULL DEFAULT true,
    updated_at     timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE procedure_lab_4.bookings (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id    bigint NOT NULL REFERENCES procedure_lab_4.sessions(id),
    customer_name text NOT NULL,
    seats         integer NOT NULL CHECK (seats > 0),
    status        text NOT NULL DEFAULT 'confirmed'
                  CHECK (status IN ('confirmed', 'cancelled')),
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO procedure_lab_4.sessions(title, tutor, capacity, remaining, is_open)
VALUES
    ('Java cơ bản',       'Thầy An',   10, 10, true),
    ('Spring Boot',       'Cô Bình',    5,  5, true),
    ('PostgreSQL nâng cao','Thầy Cường', 8,  8, false);
```

Kiểm tra dữ liệu:

```sql
SELECT id, title, capacity, remaining, is_open
FROM procedure_lab_4.sessions
ORDER BY id;
```

Kết quả ban đầu:

| id | title | Capacity | Remaining | Open |
|---:|---|---:|---:|---|
| 1 | Java cơ bản | 10 | 10 | `true` |
| 2 | Spring Boot | 5 | 5 | `true` |
| 3 | PostgreSQL nâng cao | 8 | 8 | `false` |

---

## 3. Bài 1 — Đặt chỗ và trừ số chỗ còn trống

Viết procedure có chữ ký chính xác:

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_4.sp_book_session(
    IN p_session_id bigint,
    IN p_customer_name text,
    IN p_seats integer
)
LANGUAGE plpgsql
AS $$
    -- Tự viết
$$;
```

Gọi procedure bằng:

```sql
CALL procedure_lab_4.sp_book_session(2, 'Nguyễn Văn A', 2);
```

### 3.1. Quy tắc nghiệp vụ

1. `p_session_id` không được `NULL`.
2. `p_customer_name` không được rỗng (blank) và không dài quá 50 ký tự.
3. `p_seats` phải nằm trong khoảng `1..10`.
4. Session phải tồn tại và có `is_open = true`.
5. `remaining` phải lớn hơn hoặc bằng số chỗ đặt.
6. Mỗi lần gọi thành công:
   - Trừ `p_seats` khỏi `sessions.remaining`.
   - Cập nhật `sessions.updated_at`.
   - Insert đúng một row vào `bookings`.
   - `status = 'confirmed'`.
7. Sau khi insert, lấy `bookings.id` vào biến `v_booking_id` bằng `RETURNING ... INTO`.
8. In notice theo mẫu:

   ```text
   Đã đặt chỗ <booking_id> cho <customer_name> ở phiên <session_title>, <seats> chỗ, còn <remaining_after>
   ```

### 3.2. Yêu cầu kỹ thuật

- Dùng biến cục bộ cho title, số chỗ còn trước, số chỗ còn sau và booking ID.
- Dùng `SELECT ... INTO ... FOR UPDATE` để đọc và khóa session trước khi kiểm tra `remaining`.
- Hai user đặt cùng session không được cùng dùng một giá trị remaining cũ; transaction đến sau phải chờ row lock và kiểm tra lại `remaining` mới.
- Sau `SELECT`, dùng `IF NOT FOUND` để phát hiện session không tồn tại hoặc đã đóng.
- Dùng `RAISE EXCEPTION` khi input hoặc nghiệp vụ không hợp lệ.
- Không bắt `WHEN OTHERS`; để lỗi truyền về caller.
- Không viết `COMMIT` hoặc `ROLLBACK` trong procedure.

Toàn bộ `CALL` phải atomic: nếu insert booking thất bại thì `remaining` không được thay đổi; nếu trừ `remaining` thất bại thì không được có booking mới.

### 3.3. Thứ tự xử lý gợi ý

```text
Validate input
    ↓
SELECT session INTO biến FOR UPDATE
    ↓
Không tìm thấy? → RAISE EXCEPTION
    ↓
Không đủ chỗ? → RAISE EXCEPTION
    ↓
UPDATE remaining
    ↓
INSERT booking RETURNING id INTO biến
    ↓
RAISE NOTICE
```

### 3.4. Skeleton

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_4.sp_book_session(
    IN p_session_id bigint,
    IN p_customer_name text,
    IN p_seats integer
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_title           text;
    v_remaining_before integer;
    v_remaining_after  integer;
    v_booking_id       bigint;
BEGIN
    -- TODO 1: validate p_session_id, p_customer_name, p_seats

    -- TODO 2: SELECT session INTO các biến, chỉ lấy session open, FOR UPDATE

    -- TODO 3: IF NOT FOUND và kiểm tra đủ chỗ

    -- TODO 4: tính v_remaining_after rồi UPDATE sessions

    -- TODO 5: INSERT bookings RETURNING id INTO v_booking_id

    -- TODO 6: RAISE NOTICE
END;
$procedure$;
```

---

## 4. Test Bài 1 thành công

### 4.1. Đặt chỗ ở phiên Spring Boot

Lưu timestamp hiện tại vào biến `psql`, sau đó gọi procedure:

```sql
SELECT updated_at AS updated_at_before
FROM procedure_lab_4.sessions
WHERE id = 2
\gset

CALL procedure_lab_4.sp_book_session(2, 'Nguyễn Văn A', 2);
```

Kết quả notice tương tự:

```text
NOTICE: Đã đặt chỗ 1 cho Nguyễn Văn A ở phiên Spring Boot, 2 chỗ, còn 3
```

Kiểm tra:

```sql
SELECT id,
       title,
       remaining,
       updated_at,
       updated_at > :'updated_at_before'::timestamptz AS updated_at_changed
FROM procedure_lab_4.sessions
WHERE id = 2;

SELECT id, session_id, customer_name, seats, status
FROM procedure_lab_4.bookings
ORDER BY id;
```

Kết quả bắt buộc:

| Kiểm tra | Giá trị |
|---|---|
| `remaining` Spring Boot | `3` |
| `updated_at_changed` | `true` |
| Số booking | `1` |
| `session_id` | `2` |
| `customer_name` | `Nguyễn Văn A` |
| `seats` | `2` |
| `status` | `confirmed` |

---

## 5. Test lỗi Bài 1

Chạy từng lệnh riêng ở chế độ autocommit. Mỗi lệnh phải báo lỗi:

```sql
-- Session ID không được NULL
CALL procedure_lab_4.sp_book_session(NULL, 'A', 1);

-- Tên khách rỗng
CALL procedure_lab_4.sp_book_session(1, '', 1);

-- Tên khách quá dài
CALL procedure_lab_4.sp_book_session(1, repeat('x', 51), 1);

-- Số chỗ không hợp lệ
CALL procedure_lab_4.sp_book_session(1, 'A', 0);

-- Vượt giới hạn 10
CALL procedure_lab_4.sp_book_session(1, 'A', 11);

-- Không đủ chỗ: Spring Boot còn 3
CALL procedure_lab_4.sp_book_session(2, 'A', 4);

-- Session không tồn tại
CALL procedure_lab_4.sp_book_session(999, 'A', 1);

-- Session đã đóng (is_open = false)
CALL procedure_lab_4.sp_book_session(3, 'A', 1);
```

Sau tất cả các lệnh lỗi:

```sql
SELECT id, title, remaining
FROM procedure_lab_4.sessions
ORDER BY id;

SELECT count(*) AS booking_count
FROM procedure_lab_4.bookings;
```

Kết quả bắt buộc:

- Java cơ bản vẫn còn `10`.
- Spring Boot vẫn còn `3` từ lần thành công.
- PostgreSQL nâng cao vẫn còn `8`.
- `booking_count` vẫn bằng `1`; không có booking rác từ các lần lỗi.

Nếu tự bọc test trong `BEGIN`, sau một exception phải chạy `ROLLBACK` trước khi tiếp tục.

---

## 6. Test caller rollback Bài 1

Procedure không tự commit, nên caller có thể rollback cả lần gọi thành công:

```sql
BEGIN;

CALL procedure_lab_4.sp_book_session(1, 'Lê Thị B', 3);

-- Bên trong transaction: Java cơ bản tạm còn 7, tổng số booking tạm là 2.
SELECT remaining FROM procedure_lab_4.sessions WHERE id = 1;
SELECT count(*) FROM procedure_lab_4.bookings;

ROLLBACK;

-- Sau rollback: Java cơ bản trở lại 10, tổng số booking trở lại 1.
SELECT remaining FROM procedure_lab_4.sessions WHERE id = 1;
SELECT count(*) FROM procedure_lab_4.bookings;
```

---

## 7. Test race condition Bài 1 bằng hai session

Chuẩn hóa Spring Boot về `remaining = 5`:

```sql
DELETE FROM procedure_lab_4.bookings WHERE session_id = 2;

UPDATE procedure_lab_4.sessions
SET remaining = 5,
    updated_at = clock_timestamp()
WHERE id = 2;
```

Mở cửa sổ `psql` A:

```sql
BEGIN;
CALL procedure_lab_4.sp_book_session(2, 'User A', 3);
SELECT pg_sleep(10); -- giữ row lock để cửa sổ B có thời gian chạy
COMMIT;
```

Trong lúc A đang sleep, mở cửa sổ `psql` B:

```sql
\timing on
CALL procedure_lab_4.sp_book_session(2, 'User B', 3);
```

B phải chờ A commit, sau đó báo không đủ chỗ. Kiểm tra:

```sql
SELECT remaining
FROM procedure_lab_4.sessions
WHERE id = 2;

SELECT count(*) AS successful_bookings,
       COALESCE(SUM(seats), 0) AS booked_seats
FROM procedure_lab_4.bookings
WHERE session_id = 2;
```

Kết quả bắt buộc:

- Chỉ một lời gọi thành công.
- `remaining = 2`.
- `successful_bookings = 1`.
- `booked_seats = 3`.
- Không có overbooking và `remaining` không âm.

---

## 8. Bài 2 — Hủy chỗ và hoàn lại số chỗ

Sau khi hoàn thành Bài 1, viết thêm procedure có chữ ký chính xác:

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_4.sp_cancel_booking(
    IN p_booking_id bigint
)
LANGUAGE plpgsql
AS $$
    -- Tự viết
$$;
```

Gọi procedure bằng:

```sql
CALL procedure_lab_4.sp_cancel_booking(1);
```

### 8.1. Quy tắc nghiệp vụ

1. `p_booking_id` không được `NULL`.
2. Booking phải tồn tại.
3. Chỉ booking có `status = 'confirmed'` mới được hủy.
4. Mỗi lần hủy thành công:
   - Đổi `bookings.status` thành `cancelled`.
   - Cộng lại đúng `bookings.seats` vào `sessions.remaining`.
   - Cập nhật `sessions.updated_at`.
5. Một booking chỉ được hoàn chỗ đúng một lần. Nếu gọi lại procedure cho booking đã `cancelled`, procedure phải báo lỗi và `remaining` không đổi.
6. In notice theo mẫu:

   ```text
   Đã hủy chỗ <booking_id> của <customer_name>, hoàn <seats> chỗ phiên <session_title>, còn <new_remaining>
   ```

### 8.2. Yêu cầu kỹ thuật và race condition

- Dùng `SELECT ... INTO ... FOR UPDATE` để đọc và khóa row booking trước khi kiểm tra `status`.
- Lấy `session_id`, `customer_name`, `seats` và `status` của booking vào các biến cục bộ.
- Kiểm tra `IF NOT FOUND` ngay sau `SELECT` để phát hiện booking không tồn tại.
- Chỉ sau khi xác nhận trạng thái `confirmed` mới được update booking và hoàn chỗ.
- Dùng `UPDATE sessions ... RETURNING title, remaining INTO ...` để lấy title và số chỗ mới.
- Không bắt `WHEN OTHERS`; để lỗi truyền về caller.
- Không viết `COMMIT` hoặc `ROLLBACK` trong procedure.

Hai transaction cùng hủy một booking phải hoạt động như sau:

```text
Transaction A khóa booking và hủy thành công
    ↓
Transaction B chờ row lock
    ↓
A COMMIT
    ↓
B đọc trạng thái mới là cancelled và báo lỗi
    ↓
Chỗ chỉ được hoàn một lần
```

Nếu kiểm tra trạng thái mà không khóa booking, cả A và B có thể cùng đọc `confirmed` rồi cùng cộng chỗ. Đây là race condition làm sai số chỗ dù constraint `remaining >= 0` vẫn hợp lệ.

### 8.3. Thứ tự xử lý gợi ý

```text
Validate p_booking_id
    ↓
SELECT booking INTO biến FOR UPDATE
    ↓
Không tìm thấy? → RAISE EXCEPTION
    ↓
Status khác confirmed? → RAISE EXCEPTION
    ↓
UPDATE booking thành cancelled
    ↓
UPDATE session cộng chỗ RETURNING title, remaining INTO biến
    ↓
RAISE NOTICE
```

### 8.4. Skeleton

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_4.sp_cancel_booking(
    IN p_booking_id bigint
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_session_id       bigint;
    v_customer_name    text;
    v_seats            integer;
    v_booking_status   text;
    v_session_title    text;
    v_new_remaining    integer;
BEGIN
    -- TODO 1: validate p_booking_id

    -- TODO 2: SELECT booking INTO các biến và FOR UPDATE

    -- TODO 3: IF NOT FOUND và kiểm tra status = 'confirmed'

    -- TODO 4: UPDATE booking thành cancelled

    -- TODO 5: hoàn chỗ, cập nhật updated_at và RETURNING INTO

    -- TODO 6: RAISE NOTICE
END;
$procedure$;
```

### 8.5. Test Bài 2 thành công

Chuẩn hóa dữ liệu để test không phụ thuộc các bước trước, sau đó tạo một booking mới:

```sql
DELETE FROM procedure_lab_4.bookings;

UPDATE procedure_lab_4.sessions
SET remaining = 5,
    updated_at = clock_timestamp()
WHERE id = 2;

CALL procedure_lab_4.sp_book_session(2, 'Trần Văn C', 2);

SELECT id AS cancel_booking_id
FROM procedure_lab_4.bookings
ORDER BY id DESC
LIMIT 1
\gset

SELECT remaining FROM procedure_lab_4.sessions WHERE id = 2;
CALL procedure_lab_4.sp_cancel_booking(:cancel_booking_id);
```

Kiểm tra:

```sql
SELECT id, status
FROM procedure_lab_4.bookings
WHERE id = :cancel_booking_id;

SELECT id, title, remaining
FROM procedure_lab_4.sessions
WHERE id = 2;
```

Kết quả bắt buộc:

- Trước khi hủy, `remaining` Spring Boot bằng `3`.
- Sau khi hủy, booking có `status = 'cancelled'`.
- `remaining` Spring Boot trở lại `5`.

### 8.6. Test lỗi và tính atomic

Mỗi lệnh sau phải báo lỗi:

```sql
-- Booking ID không được NULL
CALL procedure_lab_4.sp_cancel_booking(NULL);

-- Booking không tồn tại
CALL procedure_lab_4.sp_cancel_booking(999999);

-- Booking vừa test đã cancelled: không được hoàn chỗ lần thứ hai
CALL procedure_lab_4.sp_cancel_booking(:cancel_booking_id);
```

Kiểm tra lại `remaining` Spring Boot vẫn bằng `5`:

```sql
SELECT remaining
FROM procedure_lab_4.sessions
WHERE id = 2;
```

### 8.7. Test hai session cùng hủy một booking

Chuẩn hóa Spring Boot, tạo booking mua `3` chỗ và lưu ID vào biến `psql`:

```sql
DELETE FROM procedure_lab_4.bookings WHERE session_id = 2;

UPDATE procedure_lab_4.sessions
SET remaining = 5,
    updated_at = clock_timestamp()
WHERE id = 2;

CALL procedure_lab_4.sp_book_session(2, 'User A', 3);

SELECT id AS race_booking_id
FROM procedure_lab_4.bookings
WHERE session_id = 2
ORDER BY id DESC
LIMIT 1
\gset

\echo :race_booking_id
```

Ghi lại giá trị `race_booking_id`. Mở **cửa sổ A**, thay `<booking_id>` bằng giá trị vừa ghi rồi chạy:

```sql
BEGIN;
CALL procedure_lab_4.sp_cancel_booking(<booking_id>);
SELECT pg_sleep(10); -- giữ row lock để cửa sổ B có thời gian chạy
COMMIT;
```

Trong lúc A đang sleep, mở **cửa sổ B** và chạy với cùng `<booking_id>`:

```sql
\timing on
CALL procedure_lab_4.sp_cancel_booking(<booking_id>);
```

Cửa sổ B phải chờ A commit, sau đó báo booking không còn ở trạng thái `confirmed`. Kiểm tra:

```sql
SELECT id, status
FROM procedure_lab_4.bookings
WHERE id = <booking_id>;

SELECT remaining
FROM procedure_lab_4.sessions
WHERE id = 2;
```

Kết quả bắt buộc:

- Chỉ cửa sổ A hủy thành công.
- Booking có `status = 'cancelled'`.
- `remaining` Spring Boot trở lại `5`, không phải `8`.
- Cửa sổ B không hoàn chỗ lần thứ hai.

> `pg_sleep` chỉ dùng để quan sát lock trong bài lab, không đặt vào procedure production.

---

## 9. Checklist tự chấm

### Bài 1 — Đặt chỗ

- [ ] Đúng tên procedure và kiểu tham số.
- [ ] Validate `NULL`, tên rỗng/quá dài, seats nhỏ hơn `1` và lớn hơn `10`.
- [ ] Chỉ chọn session `is_open = true`.
- [ ] Có `SELECT ... INTO ... FOR UPDATE`.
- [ ] Test hai session chứng minh không overbook khi tổng số chỗ đặt lớn hơn `remaining`.
- [ ] Có `IF NOT FOUND`.
- [ ] Không cho `remaining` âm.
- [ ] Update `remaining`, đổi `updated_at` và insert booking.
- [ ] Có `RETURNING id INTO v_booking_id`.
- [ ] Có `RAISE NOTICE` đủ thông tin.
- [ ] Không có `COMMIT`, `ROLLBACK` hoặc `WHEN OTHERS`.
- [ ] Tất cả test ở mục 4–7 cho kết quả đúng.

### Bài 2 — Hủy chỗ

- [ ] Validate `p_booking_id IS NULL`.
- [ ] Khóa booking bằng `SELECT ... FOR UPDATE` trước khi kiểm tra status.
- [ ] Có `IF NOT FOUND` ngay sau `SELECT`.
- [ ] Chỉ cho phép chuyển `confirmed` thành `cancelled`.
- [ ] Hoàn đúng `seats` vào session và cập nhật `updated_at`.
- [ ] Có `UPDATE ... RETURNING title, remaining INTO ...`.
- [ ] Lần hủy thứ hai báo lỗi và không làm tăng `remaining`.
- [ ] Test hai session chứng minh chỗ chỉ được hoàn một lần.
- [ ] Không có `COMMIT`, `ROLLBACK` hoặc `WHEN OTHERS`.
- [ ] Tất cả test ở mục 8 cho kết quả đúng.

Đối chiếu sau khi tự làm: [Đáp án thực hành 4](postgresql-procedure-bai-tap-dap-an.md).

Lý thuyết liên quan: [PROCEDURE trong PostgreSQL — Chi tiết đầy đủ](../postgresql-procedure-chi-tiet.md).
