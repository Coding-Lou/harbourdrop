package com.hmdp.repository;

import com.hmdp.entity.MessageInboxEntry;
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
public interface MessageRepository extends Repository<MessageInboxEntry, MessageInboxEntry.Key> {

  @Query(
      value =
          """
          SELECT b.id AS event_id,b.kind,b.state,b.created_at,b.sent_at,
                 b.consumed_at,
                 (SELECT COUNT(*) FROM tb_message_inbox i WHERE i.event_id=b.id) AS inbox_count
          FROM tb_business_outbox b
          JOIN tb_voucher_order o ON o.id=b.aggregate_id AND o.user_id=:userId
          WHERE b.aggregate_id=:orderId
            AND b.kind IN ('ORDER_CREATED','ORDER_PAID','ORDER_CLOSED','ORDER_EXPIRE',
              'SECKILL_FULFILL')
          ORDER BY b.id LIMIT 30
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectOrderProgress(
      @Param("orderId") Long orderId, @Param("userId") Long userId);

  @Query(
      value =
          """
          SELECT *
          FROM tb_message_failure
          ORDER BY created_at DESC LIMIT 100
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectFailures();

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_message_inbox
          WHERE consumer_group=:consumerGroup AND event_id=:eventId
          """,
      nativeQuery = true)
  Long countInboxEvent(
      @Param("consumerGroup") String consumerGroup, @Param("eventId") Long eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_message_inbox(consumer_group,event_id)
          VALUES(:consumerGroup,:eventId)
          """,
      nativeQuery = true)
  int insertInboxEvent(
      @Param("consumerGroup") String consumerGroup, @Param("eventId") Long eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_message_failure(consumer_group,event_key,event_id,broker_message_id,
            payload,reason)
          VALUES(:consumerGroup,:eventKey,:eventId,:brokerMessageId,:payload,:reason)
          """,
      nativeQuery = true)
  int insertFailure(
      @Param("consumerGroup") String consumerGroup,
      @Param("eventKey") String eventKey,
      @Param("eventId") Long eventId,
      @Param("brokerMessageId") String brokerMessageId,
      @Param("payload") String payload,
      @Param("reason") String reason);
}
