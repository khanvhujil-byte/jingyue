package com.hmdp.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** MySQL is the source of truth for who liked a blog. */
public interface BlogLikeMapper {
    @Select("SELECT user_id FROM tb_blog_like WHERE blog_id = #{blogId} AND user_id = #{userId} FOR UPDATE")
    Long findUserForUpdate(@Param("blogId") Long blogId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = #{blogId} AND user_id = #{userId}")
    int countLike(@Param("blogId") Long blogId, @Param("userId") Long userId);

    @Insert("INSERT INTO tb_blog_like(user_id, blog_id, created_at) VALUES(#{userId}, #{blogId}, #{createdAt})")
    int insertLike(@Param("blogId") Long blogId, @Param("userId") Long userId,
                   @Param("createdAt") long createdAt);

    @Delete("DELETE FROM tb_blog_like WHERE blog_id = #{blogId} AND user_id = #{userId}")
    int deleteLike(@Param("blogId") Long blogId, @Param("userId") Long userId);

    @Select("SELECT user_id FROM tb_blog_like WHERE blog_id = #{blogId} ORDER BY created_at, user_id LIMIT 5")
    List<Long> firstLikers(@Param("blogId") Long blogId);
}
