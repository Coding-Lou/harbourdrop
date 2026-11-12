package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * Voucher order service.
 *
 * @since 2021-12-22
 */
public interface IVoucherOrderService {
  VoucherOrder getById(Long orderId);

  /** Conditional stock deduction and order save share a transaction; the user lock spans commit. */
  void persistSeckill(VoucherOrder order);

  Result seckillVoucher(Long voucherId);

  Result orderOrdinaryVoucher(Long voucherId);

  Result queryMyOrder(Long orderId);

  Result queryMyVoucherOrder(Long voucherId);

  Result cancelUnpaidOrdinaryOrder(Long orderId);

  Result payMockOrder(Long orderId, Long expectedPayCents);

  Result queryMyOrders(Integer page, boolean heldOnly);
}
