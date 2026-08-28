CREATE OR REPLACE PROCEDURE sp_cleanup_old_audit_logs()
LANGUAGE plpgsql
AS $$
BEGIN
DELETE FROM audit_log WHERE changed_at < now() - interval '90 days';
COMMIT;
END;
$$;

SELECT cron.schedule('cleanup-audit-logs', '0 2 * * *', 'CALL sp_cleanup_old_audit_logs()');


FOUND là boolean tự động được set sau nhiều loại câu lệnh (SELECT INTO, UPDATE, DELETE, INSERT ... RETURNING, FOR loop...)
— trả lời "có ít nhất 1 dòng bị ảnh hưởng/tìm thấy không", không cho biết số lượng chính xác như ROW_COUNT

UPDATE products SET stock = stock - 1 WHERE id = 999;
IF NOT FOUND THEN
    RAISE EXCEPTION 'Sản phẩm không tồn tại';
END IF;


-- Exception
EXCEPTION
    WHEN SQLSTATE '23505' THEN
        RAISE NOTICE 'Trùng dữ liệu';

CREATE OR REPLACE FUNCTION fn_register_customer(p_email text, p_full_name text)
RETURNS int
LANGUAGE plpgsql
AS $$
DECLARE
v_id int;
BEGIN
INSERT INTO customers(email, full_name) VALUES (p_email, p_full_name)
    RETURNING id INTO v_id;
RETURN v_id;

EXCEPTION
    WHEN unique_violation THEN          -- ứng với SQLSTATE 23505
        RAISE NOTICE 'Email % đã tồn tại', p_email;
RETURN (SELECT id FROM customers WHERE email = p_email);

WHEN not_null_violation THEN        -- ứng với SQLSTATE 23502
        RAISE EXCEPTION 'Thiếu thông tin bắt buộc';

WHEN foreign_key_violation THEN     -- ứng với SQLSTATE 23503
        RAISE EXCEPTION 'Dữ liệu tham chiếu không hợp lệ';

WHEN OTHERS THEN                    -- bắt tất cả lỗi còn lại
        RAISE NOTICE 'Lỗi không xác định: % (SQLSTATE: %)', SQLERRM, SQLSTATE;
        RAISE;                          -- ném lại lỗi gốc, không "nuốt" lỗi
END;
$$;