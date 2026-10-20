-- Apply once to an existing database after checking/resolving duplicate user/voucher pairs.
-- The application does not execute this migration automatically.
ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id);
