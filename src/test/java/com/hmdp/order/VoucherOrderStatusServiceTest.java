package com.hmdp.order;

import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.order.VoucherOrderStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=VoucherOrderWriterTest.Config.class)
class VoucherOrderStatusServiceTest {
    @Autowired DataSource source;
    @Autowired VoucherOrderMapper orders;
    JdbcTemplate jdbc;
    StringRedisTemplate redis;
    SetOperations<String, String> purchased;
    VoucherOrderStatusService status;

    @BeforeEach void reset() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order");
        jdbc.execute("DROP TABLE IF EXISTS tb_seckill_voucher");
        jdbc.execute("CREATE TABLE tb_voucher_order(id BIGINT PRIMARY KEY,user_id BIGINT,voucher_id BIGINT,UNIQUE(user_id,voucher_id))");
        jdbc.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY,stock INT)");
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES(10,100)");
        redis = mock(StringRedisTemplate.class);
        purchased = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(purchased);
        when(purchased.isMember(anyString(), anyString())).thenReturn(false);
        status = new VoucherOrderStatusService(orders, redis);
    }

    @Test void acceptedReservationIsPendingAndReadsNeverChangeInventory() {
        when(purchased.isMember("seckill:order:10", "8")).thenReturn(true);
        assertEquals("PENDING", status.query(8L, 10L).get("state"));
        assertEquals(100, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        verify(purchased, times(1)).isMember("seckill:order:10", "8");
    }

    @Test void committedOrderReturnsExactStringIdEvenIfRedisIsUnavailable() {
        jdbc.update("INSERT INTO tb_voucher_order VALUES(9007199254740993,8,10)");
        Map<String, Object> result = status.query(8L, 10L);
        assertEquals("CREATED", result.get("state"));
        assertEquals("9007199254740993", result.get("orderId"));
        verifyNoInteractions(redis);
    }

    @Test void anotherUsersOrderCannotBeExposedOrCachedForTheCaller() {
        jdbc.update("INSERT INTO tb_voucher_order VALUES(1001,8,10)");
        assertEquals("CREATED", status.query(8L, 10L).get("state"));
        Map<String, Object> other = status.query(9L, 10L);
        assertEquals("UNKNOWN", other.get("state"));
        assertFalse(other.containsKey("orderId"));
        assertEquals("UNKNOWN", status.query(9L, 10L).get("state"));
        verify(purchased, times(1)).isMember("seckill:order:10", "9");
    }

    @Test void uncommittedInsertNeverShowsSuccessAndCommitMakesOrderVisible() throws Exception {
        when(purchased.isMember("seckill:order:10", "8")).thenReturn(true);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE tb_seckill_voucher SET stock=99 WHERE voucher_id=10");
            statement.executeUpdate("INSERT INTO tb_voucher_order VALUES(1001,8,10)");
            assertEquals("PENDING", status.query(8L, 10L).get("state"));
            connection.commit();
        }
        // Bypass the bounded one-second read snapshot to check database visibility immediately.
        assertEquals("CREATED", new VoucherOrderStatusService(orders, redis).query(8L, 10L).get("state"));
        assertEquals(99, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
    }

    @Test void rolledBackInsertRemainsPendingAndNeverBecomesSuccess() throws Exception {
        when(purchased.isMember("seckill:order:10", "8")).thenReturn(true);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE tb_seckill_voucher SET stock=99 WHERE voucher_id=10");
            statement.executeUpdate("INSERT INTO tb_voucher_order VALUES(1001,8,10)");
            connection.rollback();
        }
        assertEquals("PENDING", status.query(8L, 10L).get("state"));
        assertEquals(100, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
    }

    @Test void missingRedisResponseIsAnErrorAndCannotBeReportedAsNoOrder() {
        when(purchased.isMember(anyString(), anyString())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> status.query(8L, 10L));
        assertThrows(IllegalArgumentException.class, () -> status.query(null, 10L));
    }
}
