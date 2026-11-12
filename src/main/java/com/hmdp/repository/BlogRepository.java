package com.hmdp.repository;

import com.hmdp.entity.Blog;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BlogRepository extends JpaRepository<Blog, Long>, InsertRepository<Blog> {

  Page<Blog> findByUserId(Long userId, Pageable pageable);

  Page<Blog> findByShopId(Long shopId, Pageable pageable);

  @Query(
      "SELECT b FROM Blog b WHERE b.shopId = :shopId"
          + " AND (b.title LIKE :pattern OR b.content LIKE :pattern)")
  Page<Blog> searchByShop(
      @Param("shopId") Long shopId, @Param("pattern") String pattern, Pageable pageable);

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_blog_like
          WHERE blog_id=:blogId AND user_id=:userId
          """,
      nativeQuery = true)
  Long countLike(@Param("blogId") Long blogId, @Param("userId") Long userId);

  @Query(
      value =
          """
          SELECT id
          FROM tb_blog
          WHERE id=:blogId FOR UPDATE
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectIdForUpdateTuples(@Param("blogId") Long blogId);

  default List<Map<String, Object>> selectIdForUpdate(Long blogId) {
    return Rows.mutable(selectIdForUpdateTuples(blogId));
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_blog_like(blog_id,user_id)
          VALUES(:blogId,:userId)
          """,
      nativeQuery = true)
  int insertLike(@Param("blogId") Long blogId, @Param("userId") Long userId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_blog
          SET liked=liked+1
          WHERE id=:blogId
          """,
      nativeQuery = true)
  int incrementLikes(@Param("blogId") Long blogId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          DELETE
          FROM tb_blog_like
          WHERE blog_id=:blogId AND user_id=:userId
          """,
      nativeQuery = true)
  int deleteLike(@Param("blogId") Long blogId, @Param("userId") Long userId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_blog
          SET liked=GREATEST(0,liked-1)
          WHERE id=:blogId
          """,
      nativeQuery = true)
  int decrementLikes(@Param("blogId") Long blogId);

  @Query(
      value =
          """
          SELECT user_id
          FROM tb_blog_like
          WHERE blog_id=:blogId
          ORDER BY created_at,user_id LIMIT 5
          """,
      nativeQuery = true)
  List<Long> selectFirstLikeUserIds(@Param("blogId") Long blogId);

  @Query(
      value =
          """
          SELECT blog_id
          FROM tb_blog_like
          WHERE user_id = :userId AND blog_id IN (:blogIds)
          """,
      nativeQuery = true)
  List<Long> selectLikedBlogIdsIn(
      @Param("userId") Long userId, @Param("blogIds") List<Long> blogIds);

  /** An empty IN list is invalid SQL, so short-circuit it. */
  default List<Long> selectLikedBlogIds(Long userId, List<Long> blogIds) {
    if (blogIds == null || blogIds.isEmpty()) {
      return List.of();
    }
    return selectLikedBlogIdsIn(userId, blogIds);
  }

  @Query(
      value =
          """
          SELECT created_at
          FROM tb_blog_like
          WHERE blog_id=:blogId AND user_id=:userId
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectLikeTimestampTuples(
      @Param("blogId") Long blogId, @Param("userId") Long userId);

  default List<Map<String, Object>> selectLikeTimestamp(Long blogId, Long userId) {
    return Rows.mutable(selectLikeTimestampTuples(blogId, userId));
  }
}
