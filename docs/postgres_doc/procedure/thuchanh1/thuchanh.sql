DROP SCHEMA IF EXISTS procedure_lab_1 CASCADE;
CREATE SCHEMA procedure_lab_1;

CREATE TABLE procedure_lab_1.products (
                                          id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                          name        text NOT NULL UNIQUE,
                                          price       numeric(14, 2) NOT NULL CHECK (price > 0),
                                          stock       integer NOT NULL CHECK (stock >= 0),
                                          is_active   boolean NOT NULL DEFAULT true,
                                          updated_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE procedure_lab_1.orders (
                                        id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                        product_id    bigint NOT NULL REFERENCES procedure_lab_1.products(id),
                                        quantity      integer NOT NULL CHECK (quantity > 0),
                                        unit_price    numeric(14, 2) NOT NULL CHECK (unit_price > 0),
                                        total_amount  numeric(14, 2) NOT NULL CHECK (total_amount > 0),
                                        status        text NOT NULL DEFAULT 'created'
                                            CHECK (status IN ('created', 'cancelled')),
                                        created_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO procedure_lab_1.products(name, price, stock, is_active)
VALUES
    ('Laptop',   20000000,  5, true),
    ('Chuột',      500000, 20, true),
    ('Bàn phím',  1000000,  8, false);




--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---
--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---
CREATE OR REPLACE PROCEDURE procedure_lab_1.sp_create_order(
    IN p_product_id bigint,
    IN p_quantity integer
)
    LANGUAGE plpgsql
AS $procedure$
DECLARE
v_product_name    text;
    v_unit_price      numeric(14, 2);
    v_current_stock   integer;
    v_remaining_stock integer;
    v_order_id        bigint;
BEGIN
    IF p_product_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_product_id không được NULL';
END IF;

    IF p_quantity IS NULL OR p_quantity NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_quantity phải nằm trong khoảng 1..100';
END IF;

    -- Race-condition guard: khóa row product đến cuối transaction.
    -- Một order đồng thời cho cùng product phải chờ tại đây.
SELECT p.name,
       p.price,
       p.stock
INTO v_product_name,
    v_unit_price,
    v_current_stock
FROM procedure_lab_1.products AS p
WHERE p.id = p_product_id
  AND p.is_active
    FOR UPDATE;

IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0002',
            MESSAGE = format(
                    'Product %s không tồn tại hoặc đã ngừng bán',
                    p_product_id
                      );
END IF;

    IF v_current_stock < p_quantity THEN
        RAISE EXCEPTION USING
            ERRCODE = 'P0001',
            MESSAGE = format(
                    'Không đủ tồn kho cho %s: còn %s, yêu cầu %s',
                    v_product_name,
                    v_current_stock,
                    p_quantity
                      );
END IF;

    v_remaining_stock := v_current_stock - p_quantity;

UPDATE procedure_lab_1.products
SET stock = v_remaining_stock,
    updated_at = clock_timestamp()
WHERE id = p_product_id;

INSERT INTO procedure_lab_1.orders(
    product_id,
    quantity,
    unit_price,
    total_amount,
    status
)
VALUES (
           p_product_id,
           p_quantity,
           v_unit_price,
           v_unit_price * p_quantity,
           'created'
       )
    RETURNING id INTO v_order_id;

RAISE NOTICE
        'Đã tạo order % cho %, số lượng %, còn lại %',
        v_order_id,
        v_product_name,
        p_quantity,
        v_remaining_stock;
END;
$procedure$;
------ --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---
--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---

CREATE OR REPLACE PROCEDURE procedure_lab_1.sp_create_order_1(
    IN p_product_id bigint,
    IN p_quantity integer
)
    LANGUAGE plpgsql
AS $procedure$
DECLARE
v_product_name    text;
    v_unit_price      numeric(14, 2);
    v_current_stock   integer;
    v_remaining_stock integer;
    v_order_id        bigint;
    v_constraint_name text;
    v_error_detail    text;
BEGIN
    IF p_product_id IS NULL THEN
        RAISE EXCEPTION USING
            ERRCODE = '22004',
            MESSAGE = 'p_product_id không được NULL';
END IF;

    IF p_quantity IS NULL OR p_quantity NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION USING
            ERRCODE = '22023',
            MESSAGE = 'p_quantity phải nằm trong khoảng 1..100';
END IF;

BEGIN
        -- STRICT phát sinh NO_DATA_FOUND nếu product không tồn tại
        -- hoặc không active. FOR UPDATE vẫn chống overselling.
SELECT p.name,
       p.price,
       p.stock
INTO STRICT
    v_product_name,
            v_unit_price,
            v_current_stock
FROM procedure_lab_1.products AS p
WHERE p.id = p_product_id
  AND p.is_active
    FOR UPDATE;

IF v_current_stock < p_quantity THEN
            RAISE EXCEPTION USING
                ERRCODE = 'P0001',
                MESSAGE = format(
                        'Không đủ tồn kho cho %s: còn %s, yêu cầu %s',
                        v_product_name,
                        v_current_stock,
                        p_quantity
                          );
END IF;

        v_remaining_stock := v_current_stock - p_quantity;

UPDATE procedure_lab_1.products
SET stock = v_remaining_stock,
    updated_at = clock_timestamp()
WHERE id = p_product_id;

INSERT INTO procedure_lab_1.orders(
    product_id,
    quantity,
    unit_price,
    total_amount,
    status
)
VALUES (
           p_product_id,
           p_quantity,
           v_unit_price,
           v_unit_price * p_quantity,
           'created'
       )
    RETURNING id INTO v_order_id;

EXCEPTION
        WHEN no_data_found THEN
            -- Inner subtransaction đã rollback trước khi vào đây.
            RAISE EXCEPTION USING
                ERRCODE = 'P0002',
                MESSAGE = format(
                        'Product %s không tồn tại hoặc đã ngừng bán',
                        p_product_id
                          );

WHEN check_violation THEN
            -- Ví dụ: CHECK stock >= 0 hoặc constraint của orders lỗi.
            GET STACKED DIAGNOSTICS
                v_constraint_name = CONSTRAINT_NAME,
                v_error_detail = PG_EXCEPTION_DETAIL;

            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = format(
                        'Không thể tạo order do vi phạm constraint %s',
                        COALESCE(v_constraint_name, '<không xác định>')
                          ),
                DETAIL = COALESCE(v_error_detail, 'Không có thông tin chi tiết');
END;

    RAISE NOTICE
        'Đã tạo order % cho %, số lượng %, còn lại %',
        v_order_id,
        v_product_name,
        p_quantity,
        v_remaining_stock;
END;
$procedure$;
------ --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---
--- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- --- ---

CALL procedure_lab_1.sp_create_order(1, 4);
CALL procedure_lab_1.sp_create_order_1(1, 1);