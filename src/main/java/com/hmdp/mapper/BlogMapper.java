package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BlogMapper extends BaseMapper<Blog> {
    @Select("SELECT id FROM tb_blog WHERE id = #{id} FOR UPDATE")
    Long lockForLike(@Param("id") Long id);

    @Update("UPDATE tb_blog SET liked = COALESCE(liked, 0) + 1 WHERE id = #{id}")
    int incrementLike(@Param("id") Long id);

    @Update("UPDATE tb_blog SET liked = liked - 1 WHERE id = #{id} AND liked > 0")
    int decrementLike(@Param("id") Long id);
}
