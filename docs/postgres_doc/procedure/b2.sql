CREATE OR REPLACE PROCEDURE sp_transfer_stock(
    p_from_product_id int,
    p_to_product_id   int,
    p_qty             int
)
LANGUAGE plpgsql
AS $$
BEGIN
BEGIN
UPDATE products SET stock = stock - p_qty
WHERE id = p_from_product_id AND stock >= p_qty;

IF NOT FOUND THEN
            RAISE EXCEPTION 'Sản phẩm nguồn không đủ hàng';
END IF;

UPDATE products SET stock = stock + p_qty WHERE id = p_to_product_id;

COMMIT;
EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            RAISE NOTICE 'Chuyển kho thất bại: %', SQLERRM;
END;
END;
$$;