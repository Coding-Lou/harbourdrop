package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/** Flash-sale request persistence, delivery, and manual reconciliation. */
public interface ISeckillService {
  String STREAM = "stream.orders";
  String GROUP = "g1";

  void fulfillReservationFromMessage(long orderId);

  /**
   * Locks the reservation inside the order transaction; false means it already reached a terminal
   * state and stock must not be deducted again.
   */
  boolean lockReservationForFulfillment(VoucherOrder order);

  void markReservationSuccessful(long orderId);

  String queryReservationState(long orderId);

  Result queryReservationStatus(long orderId, long userId);

  Result publishActivity(long voucherId);

  /** Recovery entry point after manual review; uses different admission checks than publishing. */
  Result recoverActivity(long voucherId);

  Result retryReservation(long orderId);

  Result markReservationFailedAndCompensateRedis(long orderId);

  /** Only produces a reconciliation report; never repairs stock automatically. */
  Result queryInventoryReconciliationReport(long voucherId);

  Result queryReservationsForReview();
}
