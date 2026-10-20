-- Only for a NEW, isolated development database. Never apply to the old business database.
CREATE DATABASE IF NOT EXISTS shanhai_ticket CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shanhai_ticket;
SET NAMES utf8mb4;

CREATE TABLE `tb_blog`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) NOT NULL COMMENT '目的地id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '游记封面，最多9张，多张以\",\"隔开',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '游记内容',
  `liked` int(8) UNSIGNED NULL DEFAULT 00000000 COMMENT '点赞数量',
  `comments` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '评论数量',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT=1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_blog_comments`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT '探店id',
  `parent_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的1级评论id，如果是一级评论，则值为0',
  `answer_id` bigint(20) UNSIGNED NOT NULL COMMENT '回复的评论id',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int(8) UNSIGNED NULL DEFAULT NULL COMMENT '点赞数',
  `status` tinyint(1) UNSIGNED NULL DEFAULT NULL COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT=1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_follow`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `follow_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_seckill_voucher`  (
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的优惠券的id',
  `stock` int(8) NOT NULL COMMENT '库存',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
  `end_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '失效时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`voucher_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '限量活动票配额，与票种是一对一关系' ROW_FORMAT = Compact;

CREATE TABLE `tb_shop`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺名称',
  `type_id` bigint(20) UNSIGNED NOT NULL COMMENT '商铺类型的id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商铺图片，多个图片以\',\'隔开',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '商圈，例如陆家嘴',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '地址',
  `x` double UNSIGNED NOT NULL COMMENT '经度',
  `y` double UNSIGNED NOT NULL COMMENT '维度',
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT '均价，取整数',
  `sold` int(10) UNSIGNED NOT NULL COMMENT '销量',
  `comments` int(10) UNSIGNED NOT NULL COMMENT '评论数量',
  `score` int(2) UNSIGNED NOT NULL COMMENT '评分，1~5分，乘10保存，避免小数',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`type_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT=1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_shop_type`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
  `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT=1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '密码，加密存储',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '昵称，默认是用户id',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '人物头像',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT=1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '粉丝数量',
  `followee` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '关注的人的数量',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday` date NULL DEFAULT NULL COMMENT '生日',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_voucher`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '目的地id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '代金券标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
  `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通券；1,秒杀券',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT=1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

CREATE TABLE `tb_voucher_order`  (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
  `voucher_id` bigint(20) UNSIGNED NOT NULL COMMENT '购买的代金券id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_voucher_order_user_voucher` (`user_id`, `voucher_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

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



-- Fictional scenic data. No original users or phone numbers are copied.
INSERT INTO tb_shop_type (id,name,icon,sort) VALUES
(91001,'山野自然','',1),
(91002,'城市人文','',2),
(91003,'滨海漫游','',3),
(91004,'艺术展馆','',4);
INSERT INTO tb_shop (id,name,type_id,images,area,address,x,y,avg_price,sold,comments,score,open_hours) VALUES
(92001,'青岚山谷',91001,'./assets/valley.svg','深圳 · 山野自然','山海示范区 · 青岚山谷入口',114.05,22.55,68,0,0,0,'08:30-18:00'),
(92002,'白屿海岸',91003,'./assets/coast.svg','深圳 · 滨海漫游','山海示范区 · 白屿海岸步道',114.05,22.55,49,0,0,0,'09:00-19:00'),
(92003,'南城旧巷',91002,'./assets/town.svg','深圳 · 城市人文','山海示范区 · 南城文化街区',114.05,22.55,39,0,0,0,'10:00-20:00'),
(92004,'山海艺术馆',91004,'./assets/gallery.svg','深圳 · 艺术展馆','山海示范区 · 艺术中心',114.05,22.55,58,0,0,0,'10:00-18:00');
INSERT INTO tb_user (id,phone,password,nick_name,icon) VALUES
(95001,'00000095001','','林间来信',''),
(95002,'00000095002','','一颗海盐','');
INSERT INTO tb_blog (id,shop_id,user_id,title,images,content,liked,comments) VALUES
(94001,92001,95001,'把周末还给山野，走一条有风的路','./assets/valley.svg','不必把每一个周末排满。
沿着溪水慢慢走，听见树叶的声音，也听见自己。

这次选择了青岚山谷，带一瓶水和一份好心情，就足够出发。请遵守景区规定，不离开开放步道。',0,0),
(94002,92002,95002,'在海边等日落，是一件很正经的事','./assets/coast.svg','在海岸走了很久，手机放进口袋，风吹过来的时候，整个下午都变轻了。

白屿海岸是一组虚构的旅行灵感，愿你也找到自己的那片海。',0,0),
(94003,92003,95001,'转进这条小巷，城市突然慢了下来','./assets/town.svg','巷子里没有紧凑的打卡清单，只有瓦檐、树影和午后的光。

看看建筑，坐一会儿，再慢慢往前走。',0,0);

-- Create ticket products through POST /voucher/seckill AFTER starting the backend,
-- so the existing afterCommit logic can initialize Redis inventory.
