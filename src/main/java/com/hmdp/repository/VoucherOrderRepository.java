package com.hmdp.repository;

import com.hmdp.entity.VoucherOrder;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface VoucherOrderRepository
    extends JpaRepository<VoucherOrder, Long>, InsertRepository<VoucherOrder> {

  long countByUserIdAndVoucherId(Long userId, Long voucherId);

  VoucherOrder findFirstByUserIdAndVoucherIdOrderByCreateTimeDesc(Long userId, Long voucherId);

  VoucherOrder findFirstByUserIdAndVoucherIdAndStatusNotOrderByCreateTimeDesc(
      Long userId, Long voucherId, Integer status);

  Page<VoucherOrder> findByUserId(Long userId, Pageable pageable);

  Page<VoucherOrder> findByUserIdAndStatus(Long userId, Integer status, Pageable pageable);

  /** Pays only an owned, still-unpaid, not yet expired order; 0 rows means the state moved on. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_voucher_order
          SET status=:paid,pay_time=:payTime
          WHERE id=:orderId AND user_id=:userId AND status=:unpaid
            AND (expire_at IS NULL OR expire_at > NOW(3))
          """,
      nativeQuery = true)
  int markPaidIfUnpaidAndNotExpired(
      @Param("orderId") Long orderId,
      @Param("userId") Long userId,
      @Param("unpaid") Integer unpaid,
      @Param("paid") Integer paid,
      @Param("payTime") LocalDateTime payTime);

  /** Compare-and-set on the order status, scoped to the owner. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_voucher_order
          SET status=:next
          WHERE id=:orderId AND user_id=:userId AND status=:expected
          """,
      nativeQuery = true)
  int changeStatusIfCurrent(
      @Param("orderId") Long orderId,
      @Param("userId") Long userId,
      @Param("expected") Integer expected,
      @Param("next") Integer next);

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_voucher_order
          WHERE id=:orderId
          """,
      nativeQuery = true)
  Long countByOrderId(@Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT COUNT(*)
          FROM tb_voucher_order
          WHERE voucher_id=:voucherId
          """,
      nativeQuery = true)
  Long countByVoucherId(@Param("voucherId") Long voucherId);

  @Query(
      value =
          """
          SELECT id
          FROM tb_voucher_order
          WHERE status=1 AND voucher_type_snapshot=0 AND expire_at<=NOW(3)
          ORDER BY expire_at,id LIMIT 200
          """,
      nativeQuery = true)
  List<Long> selectExpiredOrdinaryOrders();

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_voucher_order
          SET expire_at=TIMESTAMPADD(MINUTE,:timeoutMinutes,NOW(3))
          WHERE id=:orderId AND status=1 AND voucher_type_snapshot=0 AND expire_at IS NULL
          """,
      nativeQuery = true)
  int initializeDeadline(
      @Param("timeoutMinutes") Integer timeoutMinutes, @Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT user_id,status,expire_at
          FROM tb_voucher_order
          WHERE id=:orderId
          """,
      nativeQuery = true)
  Map<String, Object> selectDeadline(@Param("orderId") Long orderId);

  @Query(
      value =
          """
          SELECT user_id,status+0 AS status,voucher_type_snapshot,expire_at,
            CAST(expire_at<=NOW(3) AS SIGNED) AS due
          FROM tb_voucher_order
          WHERE id=:orderId FOR UPDATE
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectExpiryStateForUpdate(@Param("orderId") Long orderId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_voucher_order
          SET status=4
          WHERE id=:orderId AND status=1 AND expire_at<=NOW(3)
          """,
      nativeQuery = true)
  int closeExpiredOrder(@Param("orderId") Long orderId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_ordinary_order_slot(user_id,voucher_id)
          VALUES(:userId,:voucherId)
          """,
      nativeQuery = true)
  int insertOrdinarySlot(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

  @Query(
      value =
          """
          SELECT user_id
          FROM tb_ordinary_order_slot
          WHERE user_id=:userId AND voucher_id=:voucherId FOR UPDATE
          """,
      nativeQuery = true)
  List<Map<String, Object>> lockOrdinarySlot(
      @Param("userId") Long userId, @Param("voucherId") Long voucherId);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT IGNORE INTO tb_order_event_history(event_id,order_id,event_type,occurred_at)
          VALUES(:eventId,:orderId,:eventType,:occurredAt)
          """,
      nativeQuery = true)
  int insertEventHistory(
      @Param("eventId") Long eventId,
      @Param("orderId") Long orderId,
      @Param("eventType") String eventType,
      @Param("occurredAt") Timestamp occurredAt);

  @Query(
      value =
          """
          SELECT shop_id_snapshot,user_id
          FROM tb_voucher_order
          WHERE id=:orderId
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectBehaviorSnapshot(@Param("orderId") Long orderId);
}
