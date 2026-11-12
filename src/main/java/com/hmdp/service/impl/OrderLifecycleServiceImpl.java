package com.hmdp.service.impl;

import com.hmdp.enums.OrderStatus;
import com.hmdp.enums.VoucherType;
import com.hmdp.repository.VoucherOrderRepository;
import com.hmdp.service.IBusinessOutboxService;
import com.hmdp.service.IOrderLifecycleService;
import jakarta.annotation.Resource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderLifecycleServiceImpl implements IOrderLifecycleService {

  @Resource
  private VoucherOrderRepository voucherOrderRepository;

  private final IBusinessOutboxService outbox;
  private final TransactionTemplate transactions;

  @Value("${citylens.reliability.order-timeout-minutes:30}")
  private int timeoutMinutes;

  public OrderLifecycleServiceImpl(
      IBusinessOutboxService outbox, TransactionTemplate transactions) {
    this.outbox = outbox;
    this.transactions = transactions;
  }

  /**
   * Initializes the deadline within the order-creation transaction and records the creation event
   * and the delayed close event.
   *
   * <p>Recording them does not mean the messages were sent; the relay delivers them after the
   * business commit, and on rollback the events roll back too.
   */
  @Override
  public void registerOrderCreatedEventAndDeadline(long orderId) {
    requireTransaction();
    if (timeoutMinutes < 1 || timeoutMinutes > 1440) {
      throw new IllegalStateException("Regular order timeout must be between 1 and 1440 minutes");
    }
    voucherOrderRepository.initializeDeadline(timeoutMinutes, orderId);
    Map<String, Object> row = voucherOrderRepository.selectDeadline(orderId);
    Long userId = ((Number) row.get("user_id")).longValue();
    outbox.appendBusinessEvent("ORDER_CREATED", orderId, userId);
    if (row.get("expire_at") != null) {
      outbox.appendOrderExpirationEvent(orderId, userId, (Timestamp) row.get("expire_at"));
    }
  }

  @Override
  public void registerOrderPaidEvent(long orderId, long userId) {
    requireTransaction();
    outbox.appendBusinessEvent("ORDER_PAID", orderId, userId);
  }

  @Override
  public void registerOrderClosedEvent(long orderId, long userId) {
    requireTransaction();
    outbox.appendBusinessEvent("ORDER_CLOSED", orderId, userId);
  }

  /**
   * Locks the order, re-checks its current status, type and deadline, then conditionally closes the
   * regular-voucher order.
   *
   * <p>A message that arrives early only re-records the deadline event; once payment or closing has
   * completed, no duplicate close event is produced.
   */
  @Override
  public boolean closeExpiredOrdinaryOrder(long orderId) {
    return Boolean.TRUE.equals(
        transactions.execute(
            tx -> {
              List<Map<String, Object>> rows =
                  voucherOrderRepository.selectExpiryStateForUpdate(orderId);
              if (rows.isEmpty()) {
                return false;
              }
              Map<String, Object> row = rows.get(0);
              if (!OrderStatus.UNPAID.matches(((Number) row.get("status")).intValue())
                  || row.get("voucher_type_snapshot") == null
                  || !VoucherType.ORDINARY.matches(
                      ((Number) row.get("voucher_type_snapshot")).intValue())) {
                return false;
              }
              if (row.get("expire_at") == null) {
                throw new IllegalStateException("Regular order is missing a deadline");
              }
              long userId = ((Number) row.get("user_id")).longValue();
              if (((Number) row.get("due")).intValue() == 0) {
                outbox.appendOrderExpirationEvent(
                    orderId, userId, (Timestamp) row.get("expire_at"));
                return false;
              }
              if (voucherOrderRepository.closeExpiredOrder(orderId) == 0) {
                return false;
              }
              registerOrderClosedEvent(orderId, userId);
              return true;
            }));
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Order status and events must commit in one transaction");
    }
  }

  @Override
  public void expireOverdueOrders() {
    for (Long orderId : voucherOrderRepository.selectExpiredOrdinaryOrders()) {
      closeExpiredOrdinaryOrder(orderId);
    }
  }
}
