package com.hmdp.order;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.order.VoucherOrderWriter;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VoucherOrderWriterTest.Config.class)
class VoucherOrderWriterTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = VoucherOrderMapper.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            return factory.getObject();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean VoucherOrderWriter writer(VoucherOrderMapper orders, SeckillVoucherMapper stock) {
            return new VoucherOrderWriter(orders, stock);
        }
    }
    @Autowired VoucherOrderWriter writer;
    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;

    @BeforeEach void reset() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order");
        jdbc.execute("DROP TABLE IF EXISTS tb_seckill_voucher");
        jdbc.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY, stock INT)");
        jdbc.execute("CREATE TABLE tb_voucher_order(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, voucher_id BIGINT NOT NULL, "
                + "pay_type INT DEFAULT 1, status INT DEFAULT 1, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "pay_time TIMESTAMP, use_time TIMESTAMP, refund_time TIMESTAMP, update_time TIMESTAMP, "
                + "CONSTRAINT unique_user_voucher UNIQUE(user_id, voucher_id))");
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES (10, 100)");
    }
    static VoucherOrder order(long id, long user) {
        VoucherOrder order = new VoucherOrder();
        order.setId(id); order.setUserId(user); order.setVoucherId(10L);
        return order;
    }
    @Test void concurrentReplaysCreateOneOrderAndDeductOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) results.add(pool.submit(() -> writer.create(order(1001, 1))));
            for (Future<?> result : results) result.get(15, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
            assertEquals(99, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void insertFailureRollsBackStockAndCanBeRetried() {
        jdbc.execute("ALTER TABLE tb_voucher_order ADD CONSTRAINT reject_user CHECK (user_id <> 7)");
        assertThrows(RuntimeException.class, () -> writer.create(order(1002, 7)));
        assertEquals(100, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        jdbc.execute("ALTER TABLE tb_voucher_order DROP CONSTRAINT reject_user");
        writer.create(order(1002, 7));
        assertEquals(99, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }
    @Test void differentOrderForSameUserCannotConsumeAnotherStock() {
        writer.create(order(1003, 1));
        assertThrows(IllegalStateException.class, () -> writer.create(order(1004, 1)));
        assertEquals(99, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }
    @Test void noStockIsNotAcknowledgedAsSuccessfulCreation() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 0");
        assertThrows(IllegalStateException.class, () -> writer.create(order(1005, 1)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }
}
