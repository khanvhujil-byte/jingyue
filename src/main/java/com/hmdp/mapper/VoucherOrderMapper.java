package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {
    @Select("SELECT id FROM tb_voucher_order WHERE user_id=#{userId} AND voucher_id=#{voucherId} LIMIT 1")
    Long findOwnedOrderId(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select({"<script>SELECT id,user_id,voucher_id FROM tb_voucher_order WHERE id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>"})
    List<VoucherOrder> findExistingIds(@Param("ids") List<Long> ids);

    @Select({"<script>SELECT id,user_id,voucher_id FROM tb_voucher_order WHERE voucher_id=#{voucherId} AND user_id IN",
            "<foreach collection='users' item='user' open='(' separator=',' close=')'>#{user}</foreach></script>"})
    List<VoucherOrder> findExistingUsers(@Param("voucherId") Long voucherId, @Param("users") List<Long> users);

    @Insert({"<script>INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES",
            "<foreach collection='orders' item='o' separator=','>(#{o.id},#{o.userId},#{o.voucherId})</foreach></script>"})
    int insertOrders(@Param("orders") List<VoucherOrder> orders);
}
