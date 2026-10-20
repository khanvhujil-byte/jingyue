package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.order.VoucherOrderStatusService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private VoucherOrderStatusService orderStatusService;

    @GetMapping("status/{voucherId}")
    public Result queryStatus(@PathVariable("voucherId") Long voucherId) {
        if (UserHolder.getUser() == null) return Result.fail("请先登录");
        if (voucherId <= 0) return Result.fail("优惠券参数无效");
        return Result.ok(orderStatusService.query(UserHolder.getUser().getId(), voucherId));
    }

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
