-- 仅用于独立 jingyue_ 库；不导入旧业务数据。所有时间列为 UTC epoch 毫秒。
CREATE TABLE IF NOT EXISTS tb_user (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, phone VARCHAR(20) NOT NULL UNIQUE,
 nick_name VARCHAR(80) NOT NULL, role VARCHAR(20) NOT NULL DEFAULT 'USER', icon VARCHAR(255) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_redeemer_scope (
 user_id BIGINT NOT NULL, attraction_id BIGINT NOT NULL, PRIMARY KEY(user_id,attraction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_shop (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL,
 type_id BIGINT NOT NULL, address VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL,
 images VARCHAR(1000) NOT NULL, open_hours VARCHAR(100) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_attraction_profile (
 shop_id BIGINT PRIMARY KEY, city_code VARCHAR(20) NOT NULL, city_name VARCHAR(40) NOT NULL,
 category VARCHAR(40) NOT NULL, introduction TEXT NOT NULL, admission_notice TEXT NOT NULL,
 published TINYINT NOT NULL DEFAULT 0, content_version BIGINT NOT NULL DEFAULT 1,
 KEY idx_discovery(published,city_code,category,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_product (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, attraction_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, description TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_sku (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, product_id BIGINT NOT NULL, visit_date DATE NOT NULL,
 valid_from BIGINT NOT NULL, valid_until BIGINT NOT NULL, sale_start BIGINT NOT NULL, sale_end BIGINT NOT NULL,
 price_cent INT NOT NULL, total_stock INT NOT NULL, available_stock INT NOT NULL,
 config_version BIGINT NOT NULL DEFAULT 1, epoch VARCHAR(50) NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'DRAFT', control_state VARCHAR(30) NOT NULL DEFAULT '',
 UNIQUE KEY uk_product_date(product_id,visit_date), KEY idx_sku_state(state,id),
 CHECK(price_cent>=0), CHECK(total_stock>=0), CHECK(available_stock>=0 AND available_stock<=total_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_request_result (
 order_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 client_request_id VARCHAR(80) NOT NULL, digest VARCHAR(64) NOT NULL, result VARCHAR(20) NOT NULL,
 reason VARCHAR(255) NOT NULL DEFAULT '', accepted_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE KEY uk_request(user_id,client_request_id), KEY idx_pending(result,accepted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_order (
 id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, client_request_id VARCHAR(80) NOT NULL,
 state VARCHAR(30) NOT NULL, amount_cent INT NOT NULL, attraction_id BIGINT NOT NULL,
 snapshot_json TEXT NOT NULL, created_at BIGINT NOT NULL, pay_deadline BIGINT NOT NULL,
 paid_at BIGINT NULL, closed_at BIGINT NULL, used_at BIGINT NULL,
 valid_from BIGINT NOT NULL, valid_until BIGINT NOT NULL, closed_reason VARCHAR(100) NOT NULL DEFAULT '',
 release_state VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
 UNIQUE KEY uk_order_request(user_id,client_request_id), KEY idx_user_orders(user_id,created_at,id),
 KEY idx_payment_expiry(state,pay_deadline,id), KEY idx_ticket_expiry(state,valid_until,id), KEY idx_sku_orders(sku_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_active_claim (
 user_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, order_id BIGINT NOT NULL UNIQUE,
 PRIMARY KEY(user_id,sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_payment (
 id VARCHAR(50) PRIMARY KEY, order_id BIGINT NOT NULL UNIQUE, provider_event_id VARCHAR(100) NOT NULL UNIQUE,
 amount_cent INT NOT NULL, result VARCHAR(20) NOT NULL, created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_admission_ticket (
 id VARCHAR(50) PRIMARY KEY, order_id BIGINT NOT NULL UNIQUE, nonce VARCHAR(80) NOT NULL UNIQUE,
 signing_version INT NOT NULL DEFAULT 1, state VARCHAR(20) NOT NULL, valid_from BIGINT NOT NULL, valid_until BIGINT NOT NULL,
 used_at BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_redemption (
 id VARCHAR(50) PRIMARY KEY, ticket_id VARCHAR(50) NOT NULL UNIQUE, operator_id BIGINT NOT NULL,
 attraction_id BIGINT NOT NULL, request_id VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE KEY uk_redemption_request(operator_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_outbox (
 event_id VARCHAR(100) PRIMARY KEY, aggregate_id VARCHAR(100) NOT NULL, event_type VARCHAR(30) NOT NULL,
 payload TEXT NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, last_error VARCHAR(500) NOT NULL DEFAULT '',
 KEY idx_outbox_dispatch(state,next_attempt_at,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_ticket_failure (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(100) NOT NULL UNIQUE,
 order_id VARCHAR(40) NOT NULL, sku_id VARCHAR(40) NOT NULL, original_payload MEDIUMTEXT NOT NULL,
 original_topic VARCHAR(100) NOT NULL, original_partition INT NOT NULL, original_offset BIGINT NOT NULL,
 error_code VARCHAR(80) NOT NULL, reason VARCHAR(500) NOT NULL, attempts INT NOT NULL DEFAULT 1,
 state VARCHAR(30) NOT NULL DEFAULT 'OPEN', first_at BIGINT NOT NULL, last_at BIGINT NOT NULL,
 KEY idx_failure_state(state,last_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_operation_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, actor_id BIGINT NOT NULL, action VARCHAR(60) NOT NULL,
 object_id VARCHAR(100) NOT NULL, before_state VARCHAR(100) NOT NULL, after_state VARCHAR(100) NOT NULL,
 created_at BIGINT NOT NULL, KEY idx_audit_object(object_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_blog (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, shop_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 title VARCHAR(120) NOT NULL, content TEXT NOT NULL, images VARCHAR(1500) NOT NULL DEFAULT '',
 liked INT NOT NULL DEFAULT 0, comments INT NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_blog_attraction(shop_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_blog_like (
 user_id BIGINT NOT NULL, blog_id BIGINT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(blog_id,user_id), KEY idx_likers(blog_id,created_at,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_blog_like_baseline (
 blog_id BIGINT PRIMARY KEY, anonymous_likes BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tb_media (
 id VARCHAR(80) PRIMARY KEY, user_id BIGINT NOT NULL, suffix VARCHAR(10) NOT NULL,
 created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
