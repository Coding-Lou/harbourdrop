package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import java.util.Collection;
import java.util.List;

/**
 * Voucher service.
 *
 * @since 2021-12-22
 */
public interface IVoucherService {
  Voucher getById(Long id);

  List<Voucher> listByIds(Collection<Long> ids);

  Result queryVoucherOfShop(Long shopId);

  void addSeckillVoucher(Voucher voucher);

  Result capabilities();

  Result createSeckillVoucher(Voucher voucher);

  Result createVoucher(Voucher voucher);
}
