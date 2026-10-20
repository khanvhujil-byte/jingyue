CREATE TABLE IF NOT EXISTS tb_ticket_warmup (
 sku_id BIGINT PRIMARY KEY, epoch VARCHAR(50) NOT NULL, created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Repair only the obsolete asset suffix in isolated development seeds; never change trades.
UPDATE tb_shop SET images=REPLACE(images,'.svg','.webp') WHERE images REGEXP '^/assets/(valley|town|coast|gallery)\\.svg$';
UPDATE tb_blog SET images=REPLACE(images,'.svg','.webp') WHERE images REGEXP '^/assets/(valley|town|coast|gallery)\\.svg$';
