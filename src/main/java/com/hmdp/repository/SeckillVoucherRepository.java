package com.hmdp.repository;

import com.hmdp.entity.SeckillPublication;
import com.hmdp.entity.SeckillVoucher;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀优惠券表，与优惠券是一对一关系 数据访问接口
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface SeckillVoucherRepository
    extends JpaRepository<SeckillVoucher, Long>, InsertRepository<SeckillVoucher> {

  @Query(
      value =
          """
          SELECT sv.voucher_id,a.has_published
          FROM tb_seckill_voucher sv
          LEFT JOIN tb_seckill_activity a ON a.voucher_id=sv.voucher_id
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectPublicationStatesTuples();

  default List<Map<String, Object>> selectPublicationStates() {
    return Rows.mutable(selectPublicationStatesTuples());
  }

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE tb_seckill_activity
          SET has_published=1
          WHERE voucher_id=:voucherId
          """,
      nativeQuery = true)
  int markPublished(@Param("voucherId") Long voucherId);

  @Query(
      value =
          """
          SELECT sv.*,v.status+0 AS status
          FROM tb_seckill_voucher sv
          JOIN tb_voucher v ON v.id=sv.voucher_id
          WHERE sv.voucher_id=:voucherId
          """,
      nativeQuery = true)
  Map<String, Object> selectPublicationDetailsRow(@Param("voucherId") Long voucherId);

  default SeckillPublication selectPublicationDetails(Long voucherId) {
    return SeckillPublication.fromRow(selectPublicationDetailsRow(voucherId));
  }

  @Query(
      value =
          """
          SELECT has_published
          FROM tb_seckill_activity
          WHERE voucher_id=:voucherId
          """,
      nativeQuery = true)
  Integer selectPublishedFlag(@Param("voucherId") Long voucherId);

  @Query(
      value =
          """
          SELECT a.total_stock,s.stock
          FROM tb_seckill_activity a
          JOIN tb_seckill_voucher s USING(voucher_id)
          WHERE voucher_id=:voucherId
          """,
      nativeQuery = true)
  List<Map<String, Object>> selectInventoryTuples(@Param("voucherId") Long voucherId);

  default List<Map<String, Object>> selectInventory(Long voucherId) {
    return Rows.mutable(selectInventoryTuples(voucherId));
  }

  @Query(
      value =
          """
          SELECT voucher_id
          FROM tb_seckill_activity
          """,
      nativeQuery = true)
  List<Long> selectActivityVoucherIds();

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO tb_seckill_activity(voucher_id,total_stock)
          VALUES(:voucherId,:totalStock)
          """,
      nativeQuery = true)
  int insertActivity(@Param("voucherId") Long voucherId, @Param("totalStock") Integer totalStock);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value = "UPDATE tb_seckill_voucher SET stock=stock-1 WHERE voucher_id=:voucherId AND stock>0",
      nativeQuery = true)
  int deductStock(@Param("voucherId") Long voucherId);
}
