package com.hmdp.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.order.VoucherOrderBatchWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes={VoucherOrderWriterTest.Config.class, VoucherOrderBatchWriter.class})
class VoucherOrderBatchWriterTest {
    @Autowired VoucherOrderBatchWriter writer;
    @Autowired DataSource source;
    JdbcTemplate jdbc;
    @BeforeEach void reset() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order");
        jdbc.execute("DROP TABLE IF EXISTS tb_seckill_voucher");
        jdbc.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY,stock INT NOT NULL)");
        jdbc.execute("CREATE TABLE tb_voucher_order(id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,voucher_id BIGINT NOT NULL,UNIQUE(user_id,voucher_id))");
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES(10,100),(20,100)");
    }
    VoucherOrder order(long id,long user,long voucher) { return new VoucherOrder().setId(id).setUserId(user).setVoucherId(voucher); }
    int stock(long voucher) { return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?",Integer.class,voucher); }
    int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order",Integer.class); }
    @Test void replayedBatchesAndDuplicatesWithinBatchDeductOnlyOnce() {
        List<VoucherOrder> batch=Arrays.asList(order(1,1,10),order(2,2,10),order(1,1,10),order(3,1,20));
        writer.createBatch(batch); writer.createBatch(batch);
        assertEquals(3,count()); assertEquals(98,stock(10)); assertEquals(99,stock(20));
    }
    @Test void concurrentBatchReplaysRemainIdempotent() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> results=new ArrayList<>();
            for(int i=0;i<24;i++) results.add(pool.submit(() -> writer.createBatch(Arrays.asList(order(1,1,10),order(2,2,10)))));
            for(Future<?> result:results) result.get(15,TimeUnit.SECONDS);
            assertEquals(2,count()); assertEquals(98,stock(10));
        } finally { pool.shutdownNow(); }
    }
    @Test void differentOrderForSameUserRollsBackWholeBatch() {
        writer.createBatch(Collections.singletonList(order(1,1,10)));
        assertThrows(IllegalStateException.class,()->writer.createBatch(Arrays.asList(order(2,2,10),order(3,1,10))));
        assertEquals(1,count()); assertEquals(99,stock(10));
    }
    @Test void duplicateUserInsideBatchDoesNotConsumeAnyStock() {
        assertThrows(IllegalStateException.class,()->writer.createBatch(Arrays.asList(order(1,1,10),order(2,1,10))));
        assertEquals(0,count()); assertEquals(100,stock(10));
    }
    @Test void failingInsertRollsBackAllVoucherStockAndOrdersThenRetrySucceeds() {
        jdbc.execute("ALTER TABLE tb_voucher_order ADD CONSTRAINT reject_user CHECK(user_id<>7)");
        List<VoucherOrder> batch=Arrays.asList(order(1,1,10),order(2,7,20));
        assertThrows(RuntimeException.class,()->writer.createBatch(batch));
        assertEquals(0,count()); assertEquals(100,stock(10)); assertEquals(100,stock(20));
        jdbc.execute("ALTER TABLE tb_voucher_order DROP CONSTRAINT reject_user");
        writer.createBatch(batch); assertEquals(2,count()); assertEquals(99,stock(10)); assertEquals(99,stock(20));
    }
    @Test void insufficientStockRollsBackEarlierVoucherAndCreatesNoOrders() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=20");
        assertThrows(IllegalStateException.class,()->writer.createBatch(Arrays.asList(order(1,1,10),order(2,2,20))));
        assertEquals(100,stock(10)); assertEquals(0,stock(20)); assertEquals(0,count());
    }
    @Test void sameIdWithDifferentBusinessIsRejectedWithoutDeduction() {
        writer.createBatch(Collections.singletonList(order(1,1,10)));
        assertThrows(IllegalStateException.class,()->writer.createBatch(Collections.singletonList(order(1,2,20))));
        assertEquals(1,count()); assertEquals(99,stock(10)); assertEquals(100,stock(20));
    }
}
