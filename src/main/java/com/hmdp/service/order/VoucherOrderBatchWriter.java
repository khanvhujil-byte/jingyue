package com.hmdp.service.order;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/** One transaction and one stock update per voucher, rather than one transaction per event. */
@Service
public class VoucherOrderBatchWriter {
    private final VoucherOrderMapper orders;
    private final SeckillVoucherMapper stocks;
    public VoucherOrderBatchWriter(VoucherOrderMapper orders, SeckillVoucherMapper stocks) {
        this.orders = orders; this.stocks = stocks;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void createBatch(List<VoucherOrder> input) {
        if (input == null || input.isEmpty()) return;
        Map<Long, VoucherOrder> unique = new LinkedHashMap<>();
        Map<Long, List<VoucherOrder>> byVoucher = new TreeMap<>();
        for (VoucherOrder o : input) {
            if (o == null || o.getId() == null || o.getUserId() == null || o.getVoucherId() == null)
                throw new IllegalArgumentException("订单消息缺少必要信息");
            VoucherOrder earlier = unique.putIfAbsent(o.getId(), o);
            if (earlier != null && !sameBusiness(earlier, o))
                throw new IllegalStateException("同一订单号对应不同业务，暂停消费等待对账");
        }
        for (VoucherOrder o : unique.values())
            byVoucher.computeIfAbsent(o.getVoucherId(), k -> new ArrayList<>()).add(o);
        // All writers lock inventory first. Sorting prevents cross-voucher reverse lock order.
        for (Long voucher : byVoucher.keySet()) {
            if (stocks.lockStock(voucher) == null) throw new IllegalStateException("秒杀优惠券不存在");
        }
        Map<Long, VoucherOrder> existing = new HashMap<>();
        for (VoucherOrder o : orders.findExistingIds(new ArrayList<>(unique.keySet()))) existing.put(o.getId(), o);
        List<VoucherOrder> toInsert = new ArrayList<>();
        for (Map.Entry<Long, List<VoucherOrder>> entry : byVoucher.entrySet()) {
            Map<Long, VoucherOrder> userOrders = new HashMap<>();
            List<Long> users = entry.getValue().stream().map(VoucherOrder::getUserId).distinct().collect(Collectors.toList());
            for (VoucherOrder o : orders.findExistingUsers(entry.getKey(), users)) userOrders.put(o.getUserId(), o);
            List<VoucherOrder> additions = new ArrayList<>();
            for (VoucherOrder o : entry.getValue()) {
                VoucherOrder persisted = existing.get(o.getId());
                if (persisted != null) {
                    if (!sameBusiness(persisted, o)) throw new IllegalStateException("已有订单与消息业务信息不匹配");
                    continue;
                }
                VoucherOrder other = userOrders.putIfAbsent(o.getUserId(), o);
                if (other != null) throw new IllegalStateException("同一用户存在不同订单，暂停消费等待对账");
                additions.add(o);
            }
            if (!additions.isEmpty()) {
                if (stocks.decrementStockBatch(entry.getKey(), additions.size()) != 1)
                    throw new IllegalStateException("数据库库存不足，保留整批消息等待对账");
                toInsert.addAll(additions);
            }
        }
        for (int start = 0; start < toInsert.size(); start += 500) {
            List<VoucherOrder> part = toInsert.subList(start, Math.min(start + 500, toInsert.size()));
            if (orders.insertOrders(part) != part.size()) throw new IllegalStateException("批量订单写入数量异常");
        }
    }
    private boolean sameBusiness(VoucherOrder a, VoucherOrder b) {
        return a.getUserId().equals(b.getUserId()) && a.getVoucherId().equals(b.getVoucherId());
    }
}
