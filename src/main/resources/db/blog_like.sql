-- Apply during a maintenance window, BEFORE deploying the new application.
-- This creates a table only. It does not reset counters or import legacy Redis data.
CREATE TABLE IF NOT EXISTS tb_blog_like (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    blog_id BIGINT UNSIGNED NOT NULL,
    created_at BIGINT NOT NULL COMMENT 'Unix epoch milliseconds, preserving legacy ZSet scores',
    PRIMARY KEY (id),
    UNIQUE KEY uk_blog_like_user_blog (user_id, blog_id),
    KEY idx_blog_like_time (blog_id, created_at, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve old counts whose user identities cannot be recovered. Never invent users.
CREATE TABLE IF NOT EXISTS tb_blog_like_baseline (
    blog_id BIGINT UNSIGNED NOT NULL,
    anonymous_likes INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '无法恢复用户身份的历史点赞数',
    migrated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- After importing legacy relationships, use this READ-ONLY query to find discrepancies.
-- Resolve missing history/counter mismatches before enabling the new endpoints.
SELECT b.id, b.liked AS stored_likes, COALESCE(l.actual_likes, 0) AS relation_likes,
       COALESCE(h.anonymous_likes, 0) AS historical_likes
FROM tb_blog b
LEFT JOIN (SELECT blog_id, COUNT(*) AS actual_likes FROM tb_blog_like GROUP BY blog_id) l
ON l.blog_id = b.id
LEFT JOIN tb_blog_like_baseline h ON h.blog_id = b.id
WHERE COALESCE(b.liked, 0) <> COALESCE(l.actual_likes, 0) + COALESCE(h.anonymous_likes, 0);

-- These should both be empty. Do not silently retain orphaned historical likes.
SELECT l.user_id, l.blog_id FROM tb_blog_like l LEFT JOIN tb_blog b ON b.id=l.blog_id WHERE b.id IS NULL;
-- On the business database, also check user existence:
-- SELECT l.user_id, l.blog_id FROM tb_blog_like l LEFT JOIN tb_user u ON u.id=l.user_id WHERE u.id IS NULL;
