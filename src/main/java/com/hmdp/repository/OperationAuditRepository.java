package com.hmdp.repository;

import com.hmdp.entity.OperationAudit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data repository; complex statements are native SQL kept verbatim from the former MyBatis
 * XML.
 */
public interface OperationAuditRepository extends Repository<OperationAudit, Long> {

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_operation_audit(user_id,method,path,http_status)
          VALUES(:userId,:method,:path,:httpStatus)
          """,
      nativeQuery = true)
  int insertAudit(
      @Param("userId") Long userId,
      @Param("method") String method,
      @Param("path") String path,
      @Param("httpStatus") Integer httpStatus);
}
