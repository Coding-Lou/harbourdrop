package com.hmdp.repository;

import com.hmdp.entity.Voucher;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<Voucher, Long>, InsertRepository<Voucher> {

  @Query(
      value =
          """
          SELECT id
          FROM tb_voucher
          WHERE shop_id=:shopId AND status=1
          ORDER BY id
          """,
      nativeQuery = true)
  List<Long> selectActiveVoucherIds(@Param("shopId") Long shopId);

  /** Shops among {@code shopIds} with a listed drop on sale or upcoming and not sold out. */
  @Query(
      value =
          """
          SELECT DISTINCT v.shop_id
          FROM tb_voucher v
          JOIN tb_seckill_voucher sv ON sv.voucher_id=v.id
          WHERE v.shop_id IN (:shopIds) AND v.type=1 AND v.status=1
            AND sv.end_time>:now AND sv.stock>0
          """,
      nativeQuery = true)
  List<Number> selectShopIdsWithOpenDrops(
      @Param("shopIds") List<Long> shopIds, @Param("now") LocalDateTime now);
}
