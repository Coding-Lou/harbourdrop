package com.hmdp.repository;

import com.hmdp.entity.Follow;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FollowRepository extends JpaRepository<Follow, Long>, InsertRepository<Follow> {

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_follow_edge(user_id,follow_user_id)
          VALUES(:userId,:followUserId)
          """,
      nativeQuery = true)
  int insertEdge(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          DELETE
          FROM tb_follow_edge
          WHERE user_id=:userId AND follow_user_id=:followUserId
          """,
      nativeQuery = true)
  int deleteEdge(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_follow_edge
          WHERE user_id=:userId AND follow_user_id=:followUserId
          """,
      nativeQuery = true)
  Long countEdge(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

  @Query(
      value =
          """
          SELECT a.follow_user_id
          FROM tb_follow_edge a
          JOIN tb_follow_edge b ON a.follow_user_id=b.follow_user_id
          WHERE a.user_id=:userId AND b.user_id=:otherUserId LIMIT 100
          """,
      nativeQuery = true)
  List<Long> selectCommonFollowIds(
      @Param("userId") Long userId, @Param("otherUserId") Long otherUserId);

  @Query(
      value =
          """
          SELECT id,user_id
          FROM tb_follow_edge
          WHERE follow_user_id=:authorId AND id>:cursorId AND created_at<=:publishedAt
          ORDER BY id LIMIT 200
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectFanBatch(
      @Param("authorId") Long authorId,
      @Param("cursorId") Long cursorId,
      @Param("publishedAt") Timestamp publishedAt);
}
