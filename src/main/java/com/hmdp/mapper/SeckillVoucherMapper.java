package com.hmdp.mapper;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {
    @Select("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = #{voucherId} FOR UPDATE")
    Integer lockStock(@Param("voucherId") Long voucherId);

    @Update("UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = #{voucherId} AND stock > 0")
    int decrementStock(@Param("voucherId") Long voucherId);

    @Update("UPDATE tb_seckill_voucher SET stock = stock - #{quantity} WHERE voucher_id = #{voucherId} AND stock >= #{quantity}")
    int decrementStockBatch(@Param("voucherId") Long voucherId, @Param("quantity") int quantity);
}
