package com.hmdp.repository;

import com.hmdp.entity.SeckillReservation;
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
public interface SeckillReservationRepository extends Repository<SeckillReservation, Long> {

  @Query(
      value =
          """
          SELECT *
          FROM tb_seckill_dead_letter
          ORDER BY created_at DESC LIMIT 100
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectDeadLettersTuples();

  default List<Map<String, Object>> selectDeadLetters() {
    return Rows.mutable(selectDeadLettersTuples());
  }

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_seckill_reservation
          WHERE voucher_id=:voucherId
          """,
      nativeQuery = true)
  Long countByVoucherId(@Param("voucherId") Long voucherId);

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_seckill_reservation
          WHERE state='REVIEW'
          """,
      nativeQuery = true)
  Long countReviewRequests();

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_seckill_reservation
          SET attempts=attempts+1,reason=:reason
          WHERE order_id=:orderId AND state='PROCESSING'
          """,
      nativeQuery = true)
  int recordAttempt(@Param("reason") String reason, @Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT state,attempts
          FROM tb_seckill_reservation
          WHERE order_id=:orderId
          """,
      nativeQuery = true)
  Map<String, Object> selectAttemptStateRow(@Param("orderId") Long orderId);

  default SeckillReservation selectAttemptState(Long orderId) {
    return SeckillReservation.fromRow(selectAttemptStateRow(orderId));
  }

  @Query(
      value =
          """
          SELECT state
          FROM tb_seckill_reservation
          WHERE order_id=:orderId FOR UPDATE
          """,
      nativeQuery = true)
  Map<String, Object> selectStateForUpdateRow(@Param("orderId") Long orderId);

  default SeckillReservation selectStateForUpdate(Long orderId) {
    return SeckillReservation.fromRow(selectStateForUpdateRow(orderId));
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_seckill_reservation
          SET state='REVIEW',reason=:reason
          WHERE order_id=:orderId AND state='PROCESSING'
          """,
      nativeQuery = true)
  int markProcessingForReview(@Param("reason") String reason, @Param("orderId") Long orderId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_seckill_dead_letter(message_id,order_id,payload,reason)
          VALUES(:messageId,:orderId,:payload,:reason)
          """,
      nativeQuery = true)
  int insertDeadLetter(
      @Param("messageId") String messageId,
      @Param("orderId") Long orderId,
      @Param("payload") String payload,
      @Param("reason") String reason);

  @Query(
      value =
          """
          SELECT voucher_id,user_id,state
          FROM tb_seckill_reservation
          WHERE order_id=:orderId
          """,
      nativeQuery = true)
  Map<String, Object> selectFulfillmentRow(@Param("orderId") Long orderId);

  default SeckillReservation selectFulfillment(Long orderId) {
    return SeckillReservation.fromRow(selectFulfillmentRow(orderId));
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_seckill_reservation(order_id,voucher_id,user_id)
          VALUES(:orderId,:voucherId,:userId)
          """,
      nativeQuery = true)
  int insertReservation(
      @Param("orderId") Long orderId,
      @Param("voucherId") Long voucherId,
      @Param("userId") Long userId);

  @Query(
      value =
          """
          SELECT *
          FROM tb_seckill_reservation
          WHERE order_id=:orderId
          """,
      nativeQuery = true)
  Map<String, Object> selectReservationRow(@Param("orderId") Long orderId);

  default SeckillReservation selectReservation(Long orderId) {
    return SeckillReservation.fromRow(selectReservationRow(orderId));
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_seckill_reservation
          SET state='SUCCESS',reason=NULL
          WHERE order_id=:orderId
          """,
      nativeQuery = true)
  int markSuccess(@Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT state
          FROM tb_seckill_reservation
          WHERE order_id=:orderId
          """,
      nativeQuery = true)
  String selectState(@Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT order_id,voucher_id,state,reason,created_at
          FROM tb_seckill_reservation
          WHERE order_id=:orderId AND user_id=:userId
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectOwnedRequestTuples(
      @Param("orderId") Long orderId, @Param("userId") Long userId);

  default List<Map<String, Object>> selectOwnedRequest(Long orderId, Long userId) {
    return Rows.mutable(selectOwnedRequestTuples(orderId, userId));
  }

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_seckill_reservation
          WHERE voucher_id=:voucherId AND state IN ('PROCESSING','REVIEW')
          """,
      nativeQuery = true)
  Long countUnfinishedRequests(@Param("voucherId") Long voucherId);

  @Query(
      value =
          """
          SELECT order_id,user_id
          FROM tb_seckill_reservation
          WHERE voucher_id=:voucherId AND state='SUCCESS'
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectSuccessfulBuyersTuples(@Param("voucherId") Long voucherId);

  default List<Map<String, Object>> selectSuccessfulBuyers(Long voucherId) {
    return Rows.mutable(selectSuccessfulBuyersTuples(voucherId));
  }

  @Query(
      value =
          """
          SELECT *
          FROM tb_seckill_reservation
          WHERE order_id=:orderId FOR UPDATE
          """,
      nativeQuery = true)
  Map<String, Object> selectReservationForUpdateRow(@Param("orderId") Long orderId);

  default SeckillReservation selectReservationForUpdate(Long orderId) {
    return SeckillReservation.fromRow(selectReservationForUpdateRow(orderId));
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_seckill_reservation
          SET state='PROCESSING',attempts=0
          WHERE order_id=:orderId
          """,
      nativeQuery = true)
  int resetForRetry(@Param("orderId") Long orderId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_seckill_reservation
          SET state='FAILED'
          WHERE order_id=:orderId
          """,
      nativeQuery = true)
  int markFailed(@Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT order_id,voucher_id,user_id,state,attempts,reason,updated_at
          FROM tb_seckill_reservation
          WHERE state IN ('REVIEW','FAILED')
          ORDER BY updated_at DESC LIMIT 100
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectReviewRequestsTuples();

  default List<Map<String, Object>> selectReviewRequests() {
    return Rows.mutable(selectReviewRequestsTuples());
  }
}
