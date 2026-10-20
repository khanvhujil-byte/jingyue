-- 点赞异步投影的 16 个同库分表。按 blog_id % 16 路由；不修改原有历史计数。
-- 必须先执行 db/blog_like.sql，并在停写维护窗口创建；切换回同步写前必须排空事件。
CREATE TABLE IF NOT EXISTS tb_blog_like_state_00 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_00 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_01 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_01 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_02 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_02 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_03 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_03 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_04 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_04 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_05 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_05 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_06 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_06 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_07 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_07 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_08 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_08 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_09 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_09 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_10 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_10 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_11 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_11 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_12 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_12 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_13 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_13 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_14 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_14 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_blog_like_state_15 (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL,
    liked TINYINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL,
    PRIMARY KEY (blog_id,user_id),
    KEY idx_like_version_15 (blog_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
