package com.hmdp.service.like;

import com.hmdp.dto.Result;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.entity.Blog;
import com.hmdp.service.like.async.AsyncBlogLikeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class BlogLikeService {
    private final BlogMapper blogs;
    private final BlogLikeMapper likes;
    private final TransactionTemplate writeTransaction;
    private final RetryTemplate lockRetry;
    private AsyncBlogLikeService async;

    @Autowired(required=false)
    public void setAsync(AsyncBlogLikeService async) { this.async=async; }

    public BlogLikeService(BlogMapper blogs, BlogLikeMapper likes, PlatformTransactionManager transactionManager) {
        this.blogs = blogs;
        this.likes = likes;
        writeTransaction = new TransactionTemplate(transactionManager);
        // RC avoids the absent-relationship gap locks observed in the real MySQL load test.
        // The blog row lock still coordinates all writers for the same blog across instances.
        writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // A retry must start its own transaction, never reuse a rollback-only outer transaction.
        writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        lockRetry = new RetryTemplate();
        lockRetry.setRetryPolicy(new SimpleRetryPolicy(3,
                Collections.singletonMap(PessimisticLockingFailureException.class, true)));
        ExponentialRandomBackOffPolicy backoff = new ExponentialRandomBackOffPolicy();
        backoff.setInitialInterval(20L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(100L);
        lockRetry.setBackOffPolicy(backoff);
    }

    /** Explicit desired state. Commit/rollback finishes before returning or retrying. */
    public Result setLiked(Long blogId, Long userId, boolean desired) {
        if (async != null) return async.change(blogId,userId,desired);
        return executeChange(blogId, userId, desired);
    }

    /** Compatibility for older pages that send PUT without a desired state. */
    public Result toggleLiked(Long blogId, Long userId) {
        if (async != null) return async.change(blogId,userId,null);
        return executeChange(blogId, userId, null);
    }

    private Result executeChange(Long blogId, Long userId, Boolean desired) {
        // Retry wraps the WHOLE transaction, including commit. Only known lock failures retry;
        // ambiguous network/commit failures must not blindly replay the legacy toggle operation.
        return lockRetry.execute(context -> writeTransaction.execute(status ->
                changeLocked(blogId, userId, desired)));
    }

    private Result changeLocked(Long blogId, Long userId, Boolean desired) {
        if (blogId == null || userId == null) throw new IllegalArgumentException("缺少笔记或用户信息");
        // All writers lock the same blog before inspecting the relation (also across instances).
        if (blogs.lockForLike(blogId) == null) return Result.fail("笔记不存在");
        // A locking read observes current state, rather than an earlier REPEATABLE READ snapshot.
        boolean existing = likes.findUserForUpdate(blogId, userId) != null;
        if (desired == null) desired = !existing;
        if (existing == desired) return Result.ok(desired);
        if (desired) {
            requireOne(likes.insertLike(blogId, userId, System.currentTimeMillis()));
            requireOne(blogs.incrementLike(blogId));
        } else {
            requireOne(likes.deleteLike(blogId, userId));
            requireOne(blogs.decrementLike(blogId));
        }
        return Result.ok(desired);
    }

    public boolean isLiked(Long blogId, Long userId) {
        if (async != null) return async.isLiked(blogId,userId);
        return likes.countLike(blogId, userId) > 0;
    }

    public List<Long> firstLikers(Long blogId) {
        if (async != null) return async.firstLikers(blogId);
        return likes.firstLikers(blogId);
    }

    public void decorate(Blog blog, Long userId) {
        if (async != null) async.decorate(blog,userId);
        else blog.setIsLike(userId != null && isLiked(blog.getId(),userId));
    }

    private void requireOne(int affected) {
        if (affected != 1) throw new IllegalStateException("点赞关系与计数不一致，事务已中止，请对账");
    }
}
