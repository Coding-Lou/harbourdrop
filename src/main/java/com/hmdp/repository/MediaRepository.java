package com.hmdp.repository;

import com.hmdp.entity.MediaOwner;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data repository; complex statements are native SQL kept verbatim from the former MyBatis
 * XML.
 */
public interface MediaRepository extends Repository<MediaOwner, String> {

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_media_owner(path,user_id)
          VALUES(:path,:userId)
          """,
      nativeQuery = true)
  int insertOwner(@Param("path") String path, @Param("userId") Long userId);

  @Query(
      value =
          """
          SELECT user_id,state
          FROM tb_media_owner
          WHERE path=:path FOR UPDATE
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectOwnerForUpdate(@Param("path") String path);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_blog_media(blog_id,path)
          VALUES(:blogId,:path)
          """,
      nativeQuery = true)
  int insertBlogReference(@Param("blogId") Long blogId, @Param("path") String path);

  @Query(
      value =
          """
          SELECT blog_id
          FROM tb_blog_media
          WHERE path=:path LIMIT 1 FOR UPDATE
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectReferencesForUpdate(@Param("path") String path);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_media_owner
          SET state='DELETING'
          WHERE path=:path
          """,
      nativeQuery = true)
  int markDeleting(@Param("path") String path);

  @Query(
      value =
          """
          SELECT state
          FROM tb_media_owner
          WHERE path=:path AND user_id=:userId
          """,
      nativeQuery = true)
  List<String> selectOwnedStates(@Param("path") String path, @Param("userId") Long userId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_media_owner
          SET state='DELETED'
          WHERE path=:path AND state='DELETING'
          """,
      nativeQuery = true)
  int markDeleted(@Param("path") String path);
}
