package com.hmdp.repository;

import com.hmdp.entity.BehaviorEventRecord;
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
public interface BehaviorRepository extends Repository<BehaviorEventRecord, String> {

  @Query(
      value =
          """
          SELECT *
          FROM tb_behavior_outbox
          WHERE state<>'SENT'
          ORDER BY created_at LIMIT 100
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectUnsentEvents();

  @Query(
      value =
          """
          SELECT *
          FROM tb_behavior_failure
          ORDER BY created_at DESC LIMIT 100
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectFailures();

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_behavior_outbox
          SET state='PENDING',attempts=0,next_attempt_at=NOW(3),lease_owner=NULL,lease_until=NULL,
            last_error=NULL
          WHERE event_id=:eventId AND state='REVIEW'
          """,
      nativeQuery = true)
  int retryReviewEvent(@Param("eventId") String eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_behavior_failure(topic,partition_id,message_offset,payload,reason)
          VALUES(:topic,:partitionId,:messageOffset,:payload,:reason)
          """,
      nativeQuery = true)
  int insertFailure(
      @Param("topic") String topic,
      @Param("partitionId") Integer partitionId,
      @Param("messageOffset") Long messageOffset,
      @Param("payload") String payload,
      @Param("reason") String reason);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_behavior_event(event_id,shop_id,user_id,action,occurred_at_ms,
            bucket_ms)
          VALUES(:eventId,:shopId,:userId,:action,:occurredAtMs,:bucketMs)
          """,
      nativeQuery = true)
  int insertEvent(
      @Param("eventId") String eventId,
      @Param("shopId") Long shopId,
      @Param("userId") Long userId,
      @Param("action") String action,
      @Param("occurredAtMs") Long occurredAtMs,
      @Param("bucketMs") Long bucketMs);

  @Query(
      value =
          """
          SELECT shop_id,user_id,action,occurred_at_ms
          FROM tb_behavior_event
          WHERE event_id=:eventId
          """,
      nativeQuery = true)
  Map<String, Object> selectEvent(@Param("eventId") String eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_shop_heat_bucket(shop_id,bucket_ms,visits,orders,payments)
          VALUES(:shopId,:bucketMs,:visits,:orders,:payments)
          ON DUPLICATE KEY UPDATE visits=visits+VALUES(visits),orders=orders+VALUES(orders),
            payments=payments+VALUES(payments)
          """,
      nativeQuery = true)
  int accumulateBucket(
      @Param("shopId") Long shopId,
      @Param("bucketMs") Long bucketMs,
      @Param("visits") Integer visits,
      @Param("orders") Integer orders,
      @Param("payments") Integer payments);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_shop_visit_user(shop_id,user_id,bucket_ms)
          VALUES(:shopId,:userId,:bucketMs)
          """,
      nativeQuery = true)
  int insertVisitUser(
      @Param("shopId") Long shopId, @Param("userId") Long userId, @Param("bucketMs") Long bucketMs);

  @Query(
      value =
          """
          SELECT b.shop_id,s.name,SUM(b.visits) AS pv,COALESCE(u.uv,0) AS logged_uv,
            SUM(b.orders) AS orders,SUM(b.payments) AS mock_payments,SUM(b.visits)+COALESCE(u.uv,
            0)*2+SUM(b.orders)*5+SUM(b.payments)*10 AS heat
          FROM tb_shop_heat_bucket b
          JOIN tb_shop s ON s.id=b.shop_id
          LEFT JOIN (SELECT shop_id,COUNT(DISTINCT user_id) AS uv
          FROM tb_shop_visit_user
          WHERE bucket_ms BETWEEN :uvFromMs AND :uvToMs
          GROUP BY shop_id) u ON u.shop_id=b.shop_id
          WHERE b.bucket_ms BETWEEN :bucketFromMs AND :bucketToMs
          GROUP BY b.shop_id,s.name,u.uv
          ORDER BY heat DESC,b.shop_id LIMIT :limit
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectRanking(
      @Param("uvFromMs") Long uvFromMs,
      @Param("uvToMs") Long uvToMs,
      @Param("bucketFromMs") Long bucketFromMs,
      @Param("bucketToMs") Long bucketToMs,
      @Param("limit") Integer limit);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_behavior_outbox(event_id,shop_id,payload)
          VALUES(:eventId,:shopId,:payload)
          """,
      nativeQuery = true)
  int insertOutboxEvent(
      @Param("eventId") String eventId,
      @Param("shopId") Long shopId,
      @Param("payload") String payload);

  @Query(
      value =
          """
          SELECT *
          FROM tb_behavior_outbox
          WHERE (state='PENDING' AND next_attempt_at<=NOW(3)) OR (state='SENDING'
            AND lease_until<NOW(3))
          ORDER BY created_at,event_id LIMIT 10 FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectClaimableEvents();

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_behavior_outbox
          SET state='SENDING',lease_owner=:leaseOwner,lease_until=TIMESTAMPADD(SECOND,60,NOW())
          WHERE event_id=:eventId
          """,
      nativeQuery = true)
  int claimEvent(@Param("leaseOwner") String leaseOwner, @Param("eventId") String eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_behavior_outbox
          SET lease_until=TIMESTAMPADD(SECOND,60,NOW())
          WHERE event_id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
          """,
      nativeQuery = true)
  int renewLease(@Param("eventId") String eventId, @Param("leaseOwner") String leaseOwner);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_behavior_outbox
          SET state='SENT',sent_at=NOW(3),lease_owner=NULL,lease_until=NULL,last_error=NULL
          WHERE event_id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
          """,
      nativeQuery = true)
  int markSent(@Param("eventId") String eventId, @Param("leaseOwner") String leaseOwner);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_behavior_outbox
          SET state=:state,attempts=:attempts,last_error=:lastError,
            next_attempt_at=TIMESTAMPADD(SECOND,:delaySeconds,NOW()),lease_owner=NULL,
            lease_until=NULL
          WHERE event_id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
          """,
      nativeQuery = true)
  int recordSendFailure(
      @Param("state") String state,
      @Param("attempts") Integer attempts,
      @Param("lastError") String lastError,
      @Param("delaySeconds") Long delaySeconds,
      @Param("eventId") String eventId,
      @Param("leaseOwner") String leaseOwner);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_behavior_outbox
          SET state='PENDING',next_attempt_at=NOW(3),sent_at=NULL
          WHERE state='SENT' AND sent_at<TIMESTAMPADD(MINUTE,-5,NOW(3)) AND NOT EXISTS (SELECT 1
          FROM tb_behavior_event e
          WHERE e.event_id=tb_behavior_outbox.event_id) LIMIT 200
          """,
      nativeQuery = true)
  int redriveMissingEvents();

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_behavior_outbox
          WHERE state IN ('PENDING','SENDING')
          """,
      nativeQuery = true)
  Long countPendingEvents();

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_behavior_outbox
          WHERE state='REVIEW'
          """,
      nativeQuery = true)
  Long countReviewEvents();
}
