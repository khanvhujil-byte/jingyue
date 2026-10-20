package com.hmdp.service.like.async;

import org.springframework.stereotype.Component;

/** Stable routing. Table identifiers come only from this fixed allow-list. */
@Component
public class LikeShardRouter {
    public static final int SHARD_COUNT = 16;
    private static final String[] TABLES = new String[SHARD_COUNT];
    static {
        for (int i = 0; i < SHARD_COUNT; i++) TABLES[i] = String.format("tb_blog_like_state_%02d", i);
    }

    public int partition(long blogId) { return (int) Math.floorMod(blogId, (long) SHARD_COUNT); }
    public String prefix(long blogId) { return prefixForPartition(partition(blogId)); }
    public String table(long blogId) { return tableForPartition(partition(blogId)); }
    public int shardCount() { return SHARD_COUNT; }
    public String tableForPartition(int partition) { checkPartition(partition); return TABLES[partition]; }
    public String prefixForPartition(int partition) { checkPartition(partition); return "like:{p" + partition + "}:"; }
    private void checkPartition(int partition) {
        if (partition < 0 || partition >= SHARD_COUNT) throw new IllegalArgumentException("无效的点赞分片");
    }
}
