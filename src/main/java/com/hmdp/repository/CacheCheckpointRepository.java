package com.hmdp.repository;

import com.hmdp.entity.CacheCheckpoint;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data repository; complex statements are native SQL kept verbatim from the former MyBatis
 * XML.
 */
public interface CacheCheckpointRepository extends Repository<CacheCheckpoint, String> {

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_cache_cdc_checkpoint(destination,batch_id,binlog_file,binlog_offset,
            event_time_ms)
          VALUES(:destination,:batchId,:binlogFile,:binlogOffset,:eventTimeMs)
          ON DUPLICATE KEY UPDATE batch_id=VALUES(batch_id),binlog_file=VALUES(binlog_file),
            binlog_offset=VALUES(binlog_offset),event_time_ms=VALUES(event_time_ms),
            processed_at=CURRENT_TIMESTAMP(3)
          """,
      nativeQuery = true)
  int saveCheckpoint(
      @Param("destination") String destination,
      @Param("batchId") Long batchId,
      @Param("binlogFile") String binlogFile,
      @Param("binlogOffset") Long binlogOffset,
      @Param("eventTimeMs") Long eventTimeMs);
}
