package com.hmdp.repository;

import com.hmdp.entity.Voucher;
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
}
