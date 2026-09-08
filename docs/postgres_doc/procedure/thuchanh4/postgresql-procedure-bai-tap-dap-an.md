# Đáp án thực hành 4 — PostgreSQL PROCEDURE đặt chỗ và hủy chỗ

Chạy phần tạo bảng và dữ liệu trong [đề bài](postgresql-procedure-bai-tap.md) trước khi tạo procedure.

---

## 1. Lời giải Bài 1 — `sp_book_session`

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_4.sp_book_session(
    IN p_session_id bigint,
    IN p_customer_name text,
    IN p_seats integer
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_title            text;
    v_remaining_before integer;
    v_remaining_after  integer;
    v_booking_id       bigint;
BEGIN
    IF p_session_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_session_id không được NULL';
    END IF;

    IF p_customer_name IS NULL
       OR length(btrim(p_customer_name)) = 0 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_customer_name không được rỗng';
    END IF;

    IF length(p_customer_name) > 50 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = format(
                'p_customer_name quá dài: %s ký tự, tối đa 50',
                length(p_customer_name)
            );
    END IF;

    IF p_seats IS NULL OR p_seats NOT BETWEEN 1 AND 10 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_seats phải nằm trong khoảng 1..10';
    END IF;

    -- Race-condition guard: khóa row session đến cuối transaction.
    -- Một lời gọi đồng thời cho cùng session phải chờ tại đây.
    SELECT s.title,
           s.remaining
    INTO v_title,
         v_remaining_before
    FROM procedure_lab_4.sessions AS s
    WHERE s.id = p_session_id
      AND s.is_open
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Session %s không tồn tại hoặc đã đóng',
                p_session_id
            );
    END IF;

    IF v_remaining_before < p_seats THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = format(
                'Không đủ chỗ cho phiên %s: còn %s, yêu cầu %s',
                v_title,
                v_remaining_before,
                p_seats
            );
    END IF;

    v_remaining_after := v_remaining_before - p_seats;

    UPDATE procedure_lab_4.sessions
    SET remaining = v_remaining_after,
        updated_at = clock_timestamp()
    WHERE id = p_session_id;

    INSERT INTO procedure_lab_4.bookings(
        session_id,
        customer_name,
        seats,
        status
    )
    VALUES (
        p_session_id,
        btrim(p_customer_name),
        p_seats,
        'confirmed'
    )
    RETURNING id INTO v_booking_id;

    RAISE NOTICE
        'Đã đặt chỗ % cho % ở phiên %, % chỗ, còn %',
        v_booking_id,
        btrim(p_customer_name),
        v_title,
        p_seats,
        v_remaining_after;
END;
$procedure$;
```

---

## 2. Vì sao procedure atomic?

Procedure không có `COMMIT`, `ROLLBACK` hoặc exception handler:

```text
SELECT session → UPDATE remaining → INSERT booking
```

Ba bước chạy trong transaction của caller. Nếu `INSERT bookings` lỗi sau khi `remaining` đã được update:

- Ở chế độ autocommit, PostgreSQL tự rollback statement transaction bị lỗi và khôi phục `remaining`.
- Trong `BEGIN ... COMMIT` thủ công, transaction chuyển sang trạng thái aborted; caller phải chạy `ROLLBACK`, khi đó `remaining` được khôi phục.

Procedure không cần tự bắt lỗi rồi rollback.

`SELECT ... FOR UPDATE` khóa session được chọn cho đến khi transaction kết thúc. Hai transaction không thể cùng đọc một giá trị `remaining` cũ rồi cùng trừ trên giá trị đó.

---

## 3. Xử lý race condition khi hai người cùng đặt chỗ

Giả sử Spring Boot còn `5` chỗ, user A và user B cùng đặt `3`:

Nếu bỏ `FOR UPDATE`, cả hai transaction có thể cùng đọc `remaining = 5`, cùng vượt qua validation, cùng ghi `remaining = 2` và tạo hai booking có tổng `seats = 6`. Constraint `remaining >= 0` không phát hiện được lỗi này vì `remaining` cuối vẫn là `2`; đây là lost update dẫn đến overbooking.

Với `FOR UPDATE`, luồng thực tế được tuần tự hóa trên row của Spring Boot:

```text
User A                                      User B
BEGIN                                       CALL sp_book_session(2, 3)
CALL sp_book_session(2, 3)                  └─ SELECT ... FOR UPDATE: chờ
└─ khóa session 2
└─ đọc remaining = 5
└─ update remaining = 2
└─ insert booking
COMMIT                                      └─ nhận khóa sau A
                                            └─ đọc row mới: remaining = 2
                                            └─ 2 < 3 → RAISE EXCEPTION
```

Kết quả: một lời gọi thành công, một lời gọi thất bại, `remaining` còn `2`. Không có overbooking và `remaining` không âm.

Ở isolation mặc định `READ COMMITTED`, câu `SELECT ... FOR UPDATE` đang chờ sẽ khóa và trả về phiên bản row mới sau khi transaction trước commit. Nếu transaction trước rollback, user B nhận row cũ và có thể đặt chỗ thành công.

Ở `REPEATABLE READ` hoặc `SERIALIZABLE`, thay vì nhận phiên bản mới, transaction chờ có thể lỗi `SQLSTATE 40001` khi row đã bị thay đổi. Ứng dụng phải retry **toàn bộ transaction**, không chỉ chạy lại riêng câu `UPDATE`.

Điểm quyết định là transaction boundary: lock chỉ được giữ đến `COMMIT`/`ROLLBACK`. Vì procedure không tự commit, một top-level `CALL` ở chế độ autocommit giữ lock xuyên suốt cả chuỗi kiểm tra remaining → update remaining → insert booking.

---

## 4. Vì sao kiểm tra `FOUND` ngay sau `SELECT`?

`FOUND` phản ánh kết quả của câu lệnh PL/pgSQL gần nhất có cập nhật nó. Vì vậy phải kiểm tra ngay sau:

```sql
SELECT ... INTO ...;

IF NOT FOUND THEN
    RAISE EXCEPTION ...;
END IF;
```

Nếu chạy một `UPDATE` hoặc câu lệnh khác trước `IF NOT FOUND`, giá trị `FOUND` có thể đã đổi và việc kiểm tra session sẽ sai.

---

## 5. Kiểm thử nhanh Bài 1

```sql
CALL procedure_lab_4.sp_book_session(2, 'Nguyễn Văn A', 2);

SELECT id, title, remaining
FROM procedure_lab_4.sessions
ORDER BY id;

SELECT id, session_id, customer_name, seats, status
FROM procedure_lab_4.bookings
ORDER BY id;
```

Kết quả chính:

```text
Spring Boot.remaining = 3
bookings.seats = 2
bookings.status = confirmed
```

Tiếp tục chạy toàn bộ test lỗi, caller rollback và hai session đồng thời trong [đề bài](postgresql-procedure-bai-tap.md).

---

## 6. Lời giải Bài 2 — `sp_cancel_booking`

```sql
CREATE OR REPLACE PROCEDURE procedure_lab_4.sp_cancel_booking(
    IN p_booking_id bigint
)
LANGUAGE plpgsql
AS $procedure$
DECLARE
    v_session_id     bigint;
    v_customer_name  text;
    v_seats          integer;
    v_booking_status text;
    v_session_title  text;
    v_new_remaining  integer;
BEGIN
    IF p_booking_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_booking_id không được NULL';
    END IF;

    -- Khóa booking trước khi đọc status. Transaction hủy cùng booking
    -- phải chờ ở đây và sẽ thấy status mới sau khi nhận được lock.
    SELECT b.session_id,
           b.customer_name,
           b.seats,
           b.status
    INTO v_session_id,
         v_customer_name,
         v_seats,
         v_booking_status
    FROM procedure_lab_4.bookings AS b
    WHERE b.id = p_booking_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format('Booking %s không tồn tại', p_booking_id);
    END IF;

    IF v_booking_status <> 'confirmed' THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = format(
                'Booking %s không thể hủy vì đang ở trạng thái %s',
                p_booking_id,
                v_booking_status
            );
    END IF;

    UPDATE procedure_lab_4.bookings
    SET status = 'cancelled'
    WHERE id = p_booking_id;

    UPDATE procedure_lab_4.sessions
    SET remaining = remaining + v_seats,
        updated_at = clock_timestamp()
    WHERE id = v_session_id
    RETURNING title, remaining
    INTO v_session_title, v_new_remaining;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                'Session %s của booking %s không tồn tại',
                v_session_id,
                p_booking_id
            );
    END IF;

    RAISE NOTICE
        'Đã hủy chỗ % của %, hoàn % chỗ phiên %, còn %',
        p_booking_id,
        v_customer_name,
        v_seats,
        v_session_title,
        v_new_remaining;
END;
$procedure$;
```

---

## 7. Vì sao không bị hoàn chỗ hai lần?

Điểm quan trọng là khóa row booking **trước** khi kiểm tra `status`:

```text
User A                                      User B
BEGIN                                       CALL sp_cancel_booking(10)
CALL sp_cancel_booking(10)                  └─ SELECT ... FOR UPDATE: chờ
└─ khóa booking 10
└─ đọc status = confirmed
└─ đổi status = cancelled
└─ cộng remaining
COMMIT                                      └─ nhận khóa sau A
                                            └─ đọc status = cancelled
                                            └─ RAISE EXCEPTION
                                            └─ không cộng remaining
```

Nếu chỉ `SELECT` thông thường, A và B có thể cùng đọc `status = 'confirmed'` trước khi transaction kia update. Khi đó cả hai đều vượt qua validation và có thể cộng chỗ hai lần.

Ở isolation mặc định `READ COMMITTED`, transaction B đang chờ `FOR UPDATE` sẽ đọc phiên bản row mới sau khi A commit. Vì vậy B thấy `cancelled` và bị chặn bởi quy tắc nghiệp vụ. Nếu A rollback, B thấy lại `confirmed` và có thể hủy hợp lệ.

Khóa được giữ đến cuối transaction. Procedure không tự `COMMIT` hoặc `ROLLBACK`, nên việc đổi trạng thái booking và cộng `remaining` thuộc cùng một transaction atomic. Nếu `UPDATE sessions` hoặc bất kỳ lệnh sau đó lỗi, thay đổi `bookings.status` cũng bị rollback.

> Việc kiểm tra `status` chỉ chống hoàn chỗ lặp lại. Với API thực tế, vẫn nên có idempotency key để nhận diện các request retry là cùng một thao tác.

---

## 8. Kiểm thử Bài 2

### 8.1. Hủy thành công và từ chối lần hủy thứ hai

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

-- remaining: 5 → 3 → 5.
CALL procedure_lab_4.sp_cancel_booking(:cancel_booking_id);

SELECT b.id, b.status, s.title, s.remaining
FROM procedure_lab_4.bookings AS b
JOIN procedure_lab_4.sessions AS s ON s.id = b.session_id
WHERE b.id = :cancel_booking_id;

-- Phải báo lỗi; remaining vẫn là 5.
CALL procedure_lab_4.sp_cancel_booking(:cancel_booking_id);
```

Kết quả sau lần gọi đầu tiên:

```text
status = cancelled
title = Spring Boot
remaining = 5
```

### 8.2. Hai session cùng hủy

Chạy test hai cửa sổ ở mục 8.7 của [đề bài](postgresql-procedure-bai-tap.md). Cửa sổ B phải chờ A kết thúc rồi báo lỗi trạng thái. Kết quả cuối cùng là booking `cancelled` và Spring Boot có `remaining = 5`, chứng minh chỗ chỉ được hoàn một lần.

---

## 9. Tài liệu PostgreSQL liên quan

- [Row-level locks và `SELECT ... FOR UPDATE`](https://www.postgresql.org/docs/15/explicit-locking.html#LOCKING-ROWS)
- [Transaction isolation](https://www.postgresql.org/docs/15/transaction-iso.html)
