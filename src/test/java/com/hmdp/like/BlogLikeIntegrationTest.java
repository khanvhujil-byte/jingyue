package com.hmdp.like;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.controller.BlogController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.BlogServiceImpl;
import com.hmdp.service.like.BlogLikeService;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real SQL/transactions and competing threads. No business Redis/MySQL is contacted. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BlogLikeIntegrationTest.Config.class)
class BlogLikeIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = BlogLikeMapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:likes-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            return factory.getObject();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
        @Bean BlogLikeService likes(BlogMapper blogs, BlogLikeMapper likes, PlatformTransactionManager tx) {
            return new BlogLikeService(blogs, likes, tx);
        }
        @Bean static LockFailureInjector lockFailures() { return new LockFailureInjector(); }
        @Bean BlogServiceImpl blogs() { return new BlogServiceImpl(); }
        @Bean IUserService users() { return mock(IUserService.class); }
        @Bean IFollowService follows() { return mock(IFollowService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
    }

    /** Fail after a real SQL counter write, so retry tests must prove transaction rollback. */
    static class LockFailureInjector implements BeanPostProcessor {
        final AtomicInteger remaining = new AtomicInteger(), increments = new AtomicInteger();
        volatile Supplier<RuntimeException> failure;
        volatile Integer observedIsolation;
        void clear() { remaining.set(0); increments.set(0); failure = null; observedIsolation = null; }
        void fail(int times, Supplier<RuntimeException> error) { remaining.set(times); failure = error; }
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof BlogMapper)) return bean;
            return Proxy.newProxyInstance(BlogMapper.class.getClassLoader(), new Class<?>[]{BlogMapper.class},
                    (proxy, method, args) -> {
                        Object result;
                        try { result = method.invoke(bean, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                        if (method.getName().equals("incrementLike")) {
                            increments.incrementAndGet();
                            observedIsolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
                            if (remaining.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) throw failure.get();
                        }
                        return result;
                    });
        }
    }

    @Autowired DataSource ds;
    @Autowired BlogLikeService likes;
    @Autowired IBlogService blogs;
    @Autowired IUserService users;
    @Autowired StringRedisTemplate redis;
    @Autowired LockFailureInjector lockFailures;
    JdbcTemplate jdbc;
    MockMvc http;

    @BeforeEach void resetDatabase() {
        UserHolder.removeUser();
        lockFailures.clear();
        reset(redis, users);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS tb_blog_like");
        jdbc.execute("DROP TABLE IF EXISTS tb_blog_like_baseline");
        jdbc.execute("DROP TABLE IF EXISTS tb_blog");
        jdbc.execute("CREATE TABLE tb_blog(id BIGINT PRIMARY KEY, shop_id BIGINT, user_id BIGINT, title VARCHAR(100), "
                + "images VARCHAR(100), content VARCHAR(100), liked INT NOT NULL DEFAULT 0, comments INT DEFAULT 0, "
                + "create_time TIMESTAMP, update_time TIMESTAMP)");
        // Execute the actual migration, including its read-only discrepancy query.
        new ResourceDatabasePopulator(new ClassPathResource("db/blog_like.sql")).execute(ds);
        jdbc.update("INSERT INTO tb_blog(id,user_id,liked) VALUES(100,1,0),(101,1,0)");
        BlogController controller = new BlogController();
        ReflectionTestUtils.setField(controller, "blogService", blogs);
        http = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach void cleanupIdentity() { UserHolder.removeUser(); }

    private int count() { return jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=100", Integer.class); }
    private int relations() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=100", Integer.class); }
    private void assertSuccess(Result result) { assertTrue(result.getSuccess(), result.getErrorMsg()); }
    private void asUser(long id) { UserDTO user = new UserDTO(); user.setId(id); UserHolder.saveUser(user); }

    private void concurrently(boolean desired, boolean sameUser) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                final long user = sameUser ? 8L : i + 1L;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    assertSuccess(likes.setLiked(100L, user, desired));
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) result.get(20, TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void concurrentSameUserLikesOnlyCountOnce() throws Exception {
        concurrently(true, true);
        assertEquals(1, count()); assertEquals(1, relations());
    }

    @Test void concurrentSameUserUnlikesOnlyDecrementOnce() throws Exception {
        assertSuccess(likes.setLiked(100L, 8L, true));
        concurrently(false, true);
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void concurrentDifferentUsersAreNotLost() throws Exception {
        concurrently(true, false);
        assertEquals(32, count()); assertEquals(32, relations());
    }

    @Test void repeatedExplicitRequestsPreserveDesiredState() {
        assertSuccess(likes.setLiked(100L, 8L, false));
        assertSuccess(likes.setLiked(100L, 8L, true));
        assertSuccess(likes.setLiked(100L, 8L, true));
        assertEquals(1, count()); assertEquals(1, relations());
        assertSuccess(likes.setLiked(100L, 8L, false));
        assertSuccess(likes.setLiked(100L, 8L, false));
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void failedIncrementRollsBackInsertedRelationThenCanRetry() {
        jdbc.execute("ALTER TABLE tb_blog ADD CONSTRAINT reject_like CHECK(liked=0)");
        assertThrows(RuntimeException.class, () -> likes.setLiked(100L, 8L, true));
        assertEquals(0, count()); assertEquals(0, relations());
        jdbc.execute("ALTER TABLE tb_blog DROP CONSTRAINT reject_like");
        assertSuccess(likes.setLiked(100L, 8L, true));
        assertEquals(1, count()); assertEquals(1, relations());
    }

    @Test void failedDecrementRollsBackDeletedRelationThenCanRetry() {
        likes.setLiked(100L, 8L, true);
        jdbc.execute("ALTER TABLE tb_blog ADD CONSTRAINT reject_unlike CHECK(id<>100 OR liked=1)");
        assertThrows(RuntimeException.class, () -> likes.setLiked(100L, 8L, false));
        assertEquals(1, count()); assertEquals(1, relations());
        jdbc.execute("ALTER TABLE tb_blog DROP CONSTRAINT reject_unlike");
        assertSuccess(likes.setLiked(100L, 8L, false));
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void inconsistentCounterDoesNotDeleteRelationshipOrBecomeNegative() {
        jdbc.update("INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES(8,100,1)");
        assertThrows(IllegalStateException.class, () -> likes.setLiked(100L, 8L, false));
        assertEquals(0, count()); assertEquals(1, relations());
    }

    @Test void uniqueConstraintRejectsDuplicatesButAllowsOtherPairs() {
        jdbc.update("INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES(8,100,1)");
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES(8,100,2)"));
        jdbc.update("INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES(8,101,2),(9,100,2)");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like", Integer.class));
    }

    @Test void missingBlogIsRejectedWithoutCreatingRelationship() {
        assertFalse(likes.setLiked(999L, 8L, true).getSuccess());
        assertEquals(0, relations()); assertEquals(0, count());
    }

    @Test void firstFiveUseTimeAndUserIdForStableOrdering() {
        for (long user : Arrays.asList(9L, 8L, 7L, 6L, 5L, 4L)) {
            jdbc.update("INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES(?,100,?)", user, user == 9 ? 0 : 1);
        }
        assertEquals(Arrays.asList(9L, 4L, 5L, 6L, 7L), likes.firstLikers(100L));
    }

    @Test void readsAndWritesDoNotDependOnOldRedisLikeData() {
        User author = new User(); author.setId(1L); author.setNickName("author");
        when(users.getById(any())).thenReturn(author);
        asUser(8);
        assertSuccess(blogs.likeBlog(100L));
        Blog blog = (Blog) blogs.queryBlogById(100L).getData();
        assertTrue(blog.getIsLike()); assertEquals(1, blog.getLiked());
        assertSuccess(blogs.unlikeBlog(100L));
        assertFalse(((Blog) blogs.queryBlogById(100L).getData()).getIsLike());
        verifyNoInteractions(redis);
    }

    @Test void putAndDeleteAreExplicitAndUseAuthenticatedIdentity() throws Exception {
        asUser(8);
        http.perform(put("/blog/like/100").param("liked", "true").param("userId", "9"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        http.perform(put("/blog/like/100").param("liked", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        assertEquals(1, count()); assertTrue(likes.isLiked(100L, 8L)); assertFalse(likes.isLiked(100L, 9L));
        http.perform(delete("/blog/like/100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        http.perform(delete("/blog/like/100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void missingLoginCannotWriteThroughService() {
        assertFalse(blogs.likeBlog(100L).getSuccess());
        assertFalse(blogs.unlikeBlog(100L).getSuccess());
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void legacyPutStillTogglesForExistingPages() throws Exception {
        asUser(8);
        http.perform(put("/blog/like/100")).andExpect(jsonPath("$.data").value(true));
        assertEquals(1, count());
        http.perform(put("/blog/like/100")).andExpect(jsonPath("$.data").value(false));
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void explicitFalseOnSamePutEndpointIsIdempotent() throws Exception {
        asUser(8);
        http.perform(put("/blog/like/100?liked=true")).andExpect(jsonPath("$.data").value(true));
        http.perform(put("/blog/like/100?liked=false")).andExpect(jsonPath("$.data").value(false));
        http.perform(put("/blog/like/100?liked=false")).andExpect(jsonPath("$.data").value(false));
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void newLikesAndUnlikesPreserveAnonymousHistoricalCount() {
        jdbc.update("UPDATE tb_blog SET liked=6 WHERE id=100");
        jdbc.update("INSERT INTO tb_blog_like_baseline(blog_id,anonymous_likes) VALUES(100,6)");
        likes.setLiked(100L, 8L, true);
        likes.setLiked(100L, 8L, true);
        assertEquals(7, count()); assertEquals(1, relations());
        likes.setLiked(100L, 8L, false);
        likes.setLiked(100L, 8L, false);
        assertEquals(6, count()); assertEquals(0, relations());
    }

    @Test void transientDeadlockRollsBackBothWritesBeforeRetry() {
        lockFailures.fail(1, () -> new DeadlockLoserDataAccessException("模拟死锁", null));
        assertSuccess(likes.setLiked(100L, 8L, true));
        assertEquals(2, lockFailures.increments.get());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, lockFailures.observedIsolation);
        assertEquals(1, count()); assertEquals(1, relations());
    }

    @Test void exhaustedDeadlocksStopAfterThreeWholeTransactions() {
        lockFailures.fail(10, () -> new DeadlockLoserDataAccessException("模拟持续死锁", null));
        assertThrows(DeadlockLoserDataAccessException.class, () -> likes.setLiked(100L, 8L, true));
        assertEquals(3, lockFailures.increments.get());
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void businessFailureRollsBackAndIsNotRetried() {
        lockFailures.fail(1, () -> new IllegalStateException("模拟业务错误"));
        assertThrows(IllegalStateException.class, () -> likes.setLiked(100L, 8L, true));
        assertEquals(1, lockFailures.increments.get());
        assertEquals(0, count()); assertEquals(0, relations());
    }

    @Test void legacyToggleRetriesARolledBackAttemptWithoutTogglingTwice() {
        lockFailures.fail(1, () -> new DeadlockLoserDataAccessException("模拟死锁", null));
        assertEquals(true, likes.toggleLiked(100L, 8L).getData());
        assertEquals(2, lockFailures.increments.get());
        assertEquals(1, count()); assertEquals(1, relations());
    }
}
