package com.hmdp.service;

import com.hmdp.entity.SeckillVoucher;
import java.util.Collection;
import java.util.List;

/**
 * Flash-sale voucher service (one-to-one with vouchers).
 *
 * @since 2022-01-04
 */
public interface ISeckillVoucherService {
  SeckillVoucher getById(Long voucherId);

  List<SeckillVoucher> listByIds(Collection<Long> voucherIds);

  void save(SeckillVoucher voucher);

  /** Conditional {@code stock = stock - 1 WHERE stock > 0}; false when nothing was deducted. */
  boolean deductStock(Long voucherId);
}
