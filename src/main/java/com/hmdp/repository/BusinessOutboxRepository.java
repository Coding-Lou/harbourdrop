package com.hmdp.repository;

import com.hmdp.entity.BusinessOutboxTask;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data repository. Statements that rely on row locks, SKIP LOCKED, INSERT IGNORE or
 * conditional updates are native SQL whose affected-row counts callers check.
 */
public interface BusinessOutboxRepository extends Repository<BusinessOutboxTask, Long> {

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_business_outbox(kind,aggregate_id,user_id)
          VALUES(:kind,:aggregateId,:userId)
          """,
      nativeQuery = true)
  int insertEvent(
      @Param("kind") String kind,
      @Param("aggregateId") Long aggregateId,
      @Param("userId") Long userId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_business_outbox(kind,aggregate_id,user_id,resource_path)
          VALUES('MEDIA_DELETE',:aggregateId,:userId,:resourcePath)
          """,
      nativeQuery = true)
  int insertMediaDeletion(
      @Param("aggregateId") Long aggregateId,
      @Param("userId") Long userId,
      @Param("resourcePath") String resourcePath);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_business_outbox(kind,aggregate_id,user_id,deliver_at)
          VALUES('ORDER_EXPIRE',:orderId,:userId,:deadline)
          """,
      nativeQuery = true)
  int insertDeadline(
      @Param("orderId") Long orderId,
      @Param("userId") Long userId,
      @Param("deadline") Timestamp deadline);

  @Query(
      value =
          """
          SELECT *
          FROM tb_business_outbox
          WHERE state IN ('PENDING','SENDING','SENT','REVIEW')
          ORDER BY id LIMIT 100
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectUnfinishedEventsTuples();

  default List<Map<String, Object>> selectUnfinishedEvents() {
    return Rows.mutable(selectUnfinishedEventsTuples());
  }

  @Query(
      value =
          """
          SELECT kind,aggregate_id
          FROM tb_business_outbox
          WHERE id=:eventId AND state='REVIEW'
          """,
      nativeQuery = true)
  Map<String, Object> selectReviewEventRow(@Param("eventId") Long eventId);

  default BusinessOutboxTask selectReviewEvent(Long eventId) {
    return BusinessOutboxTask.fromRow(selectReviewEventRow(eventId));
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='PENDING',attempts=0,consume_attempts=0,next_attempt_at=NOW(),lease_owner=NULL,
            lease_until=NULL,last_error=NULL
          WHERE id=:eventId AND state='REVIEW'
          """,
      nativeQuery = true)
  int retryReviewEvent(@Param("eventId") Long eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_business_outbox(kind,aggregate_id,user_id,idempotency_key)
          VALUES('SECKILL_FULFILL',:orderId,:userId,:idempotencyKey)
          """,
      nativeQuery = true)
  int insertSeckillFulfillment(
      @Param("orderId") Long orderId,
      @Param("userId") Long userId,
      @Param("idempotencyKey") String idempotencyKey);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='PENDING',attempts=0,consume_attempts=0,next_attempt_at=NOW(),last_error=NULL
          WHERE idempotency_key=:idempotencyKey AND state='REVIEW'
          """,
      nativeQuery = true)
  int retryFulfillment(@Param("idempotencyKey") String idempotencyKey);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_business_outbox(kind,aggregate_id)
          VALUES('SECKILL_RETRY',:orderId)
          """,
      nativeQuery = true)
  int insertSeckillRetry(@Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT *
          FROM tb_business_outbox
          WHERE id=:eventId FOR UPDATE
          """,
      nativeQuery = true)
  Map<String, Object> selectEventForUpdateRow(@Param("eventId") Long eventId);

  default BusinessOutboxTask selectEventForUpdate(Long eventId) {
    return BusinessOutboxTask.fromRow(selectEventForUpdateRow(eventId));
  }

  @Query(
      value =
          """
          SELECT * FROM tb_business_outbox WHERE id=:eventId
          """,
      nativeQuery = true)
  Map<String, Object> selectEventByIdRow(@Param("eventId") Long eventId);

  default BusinessOutboxTask selectEventById(Long eventId) {
    return BusinessOutboxTask.fromRow(selectEventByIdRow(eventId));
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='DONE',lease_owner=NULL,lease_until=NULL
          WHERE id=:eventId
          """,
      nativeQuery = true)
  int markDuplicateDone(@Param("eventId") Long eventId);

  @Query(
      value =
          """
          SELECT cursor_id
          FROM tb_business_outbox
          WHERE id=:eventId
          """,
      nativeQuery = true)
  Long selectCursor(@Param("eventId") Long eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_business_outbox(kind,aggregate_id,user_id,cursor_id,created_at)
          VALUES(:kind,:aggregateId,:userId,:cursorId,:createdAt)
          """,
      nativeQuery = true)
  int insertFeedContinuation(
      @Param("kind") String kind,
      @Param("aggregateId") Long aggregateId,
      @Param("userId") Long userId,
      @Param("cursorId") Long cursorId,
      @Param("createdAt") Timestamp createdAt);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='DONE',consumed_at=NOW(3),lease_owner=NULL,lease_until=NULL,last_error=NULL
          WHERE id=:eventId
          """,
      nativeQuery = true)
  int markConsumed(@Param("eventId") Long eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET consume_attempts=consume_attempts+1,last_error=:lastError
          WHERE id=:eventId
          """,
      nativeQuery = true)
  int recordConsumeFailure(@Param("lastError") String lastError, @Param("eventId") Long eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='REVIEW',lease_owner=NULL,lease_until=NULL
          WHERE id=:eventId AND state<>'DONE'
          """,
      nativeQuery = true)
  int markReview(@Param("eventId") Long eventId);

  @Query(
      value =
          """
          SELECT *
          FROM tb_business_outbox
          WHERE (state='PENDING' AND next_attempt_at<=NOW()) OR (state='SENDING'
            AND lease_until<NOW(3))
          ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectClaimableEventsRows(@Param("limit") Integer limit);

  default List<BusinessOutboxTask> selectClaimableEvents(Integer limit) {
    return selectClaimableEventsRows(limit).stream().map(BusinessOutboxTask::fromRow).toList();
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='SENDING',lease_owner=:leaseOwner,lease_until=TIMESTAMPADD(SECOND,:leaseSeconds,
            NOW(3))
          WHERE id=:eventId
          """,
      nativeQuery = true)
  int claimEvent(
      @Param("leaseOwner") String leaseOwner,
      @Param("leaseSeconds") Integer leaseSeconds,
      @Param("eventId") Long eventId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET lease_until=TIMESTAMPADD(SECOND,:leaseSeconds,NOW(3))
          WHERE id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
          """,
      nativeQuery = true)
  int renewLease(
      @Param("leaseSeconds") Integer leaseSeconds,
      @Param("eventId") Long eventId,
      @Param("leaseOwner") String leaseOwner);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='SENT',sent_at=NOW(3),lease_owner=NULL,lease_until=NULL,last_error=NULL
          WHERE id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
          """,
      nativeQuery = true)
  int markSent(@Param("eventId") Long eventId, @Param("leaseOwner") String leaseOwner);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='PENDING',next_attempt_at=deliver_at,lease_owner=NULL,lease_until=NULL
          WHERE id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
          """,
      nativeQuery = true)
  int deferDeadline(@Param("eventId") Long eventId, @Param("leaseOwner") String leaseOwner);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET attempts=:attempts,next_attempt_at=TIMESTAMPADD(SECOND,:delaySeconds,NOW()),
            state=:state,last_error=:lastError,lease_owner=NULL,lease_until=NULL
          WHERE id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
          """,
      nativeQuery = true)
  int recordSendFailure(
      @Param("attempts") Integer attempts,
      @Param("delaySeconds") Long delaySeconds,
      @Param("state") String state,
      @Param("lastError") String lastError,
      @Param("eventId") Long eventId,
      @Param("leaseOwner") String leaseOwner);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET state='PENDING',next_attempt_at=NOW(),sent_at=NULL
          WHERE state='SENT' AND sent_at<TIMESTAMPADD(SECOND,:sentAgeSeconds,NOW(3))
            AND (deliver_at IS NULL OR deliver_at<TIMESTAMPADD(SECOND,:deadlineAgeSeconds,NOW(3)))
          ORDER BY id LIMIT 200
          """,
      nativeQuery = true)
  int redriveUnconsumedEvents(
      @Param("sentAgeSeconds") Integer sentAgeSeconds,
      @Param("deadlineAgeSeconds") Integer deadlineAgeSeconds);

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_business_outbox
          WHERE state IN ('PENDING','SENDING')
          """,
      nativeQuery = true)
  Long countPendingEvents();

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_business_outbox
          WHERE state='REVIEW'
          """,
      nativeQuery = true)
  Long countReviewEvents();

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_business_outbox
          WHERE state='SENT' AND (deliver_at IS NULL OR deliver_at<=NOW(3))
          """,
      nativeQuery = true)
  Long countDueSentEvents();

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_business_outbox
          SET cursor_id=:cursorId
          WHERE id=:eventId
          """,
      nativeQuery = true)
  int updateCursor(@Param("cursorId") Long cursorId, @Param("eventId") Long eventId);
}
