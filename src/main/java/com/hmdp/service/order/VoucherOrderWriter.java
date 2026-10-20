package com.hmdp.service.order;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherOrderWriter {
    private final VoucherOrderMapper orderMapper;
    private final SeckillVoucherMapper stockMapper;

    public VoucherOrderWriter(VoucherOrderMapper orderMapper, SeckillVoucherMapper stockMapper) {
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(VoucherOrder order) {
        if (order == null || order.getId() == null || order.getUserId() == null || order.getVoucherId() == null) {
            throw new IllegalArgumentException("订单消息缺少 id/userId/voucherId");
        }
        // The row lock is held through commit, serializing duplicate messages across instances.
        Integer stock = stockMapper.lockStock(order.getVoucherId());
        if (stock == null) throw new IllegalStateException("优惠券不存在: " + order.getVoucherId());
        VoucherOrder existing = orderMapper.selectById(order.getId());
        if (existing != null) {
            if (!existing.getUserId().equals(order.getUserId()) || !existing.getVoucherId().equals(order.getVoucherId())) {
                throw new IllegalStateException("订单ID与已有业务信息不匹配");
            }
            return;
        }
        Integer count = orderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("user_id", order.getUserId()).eq("voucher_id", order.getVoucherId()));
        if (count > 0) throw new IllegalStateException("该用户已有不同订单，需对账处理");
        if (stockMapper.decrementStock(order.getVoucherId()) != 1) {
            throw new IllegalStateException("数据库库存不足，保留消息等待对账");
        }
        VoucherOrder toSave = new VoucherOrder();
        toSave.setId(order.getId());
        toSave.setUserId(order.getUserId());
        toSave.setVoucherId(order.getVoucherId());
        if (orderMapper.insert(toSave) != 1) throw new IllegalStateException("订单插入失败");
    }
}
