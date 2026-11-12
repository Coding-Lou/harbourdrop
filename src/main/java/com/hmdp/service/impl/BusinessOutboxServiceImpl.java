package com.hmdp.service.impl;

import com.hmdp.repository.BusinessOutboxRepository;
import com.hmdp.service.IBusinessOutboxService;
import com.hmdp.service.IOutboxRelayService;
import com.hmdp.utils.CacheClient;
import jakarta.annotation.Resource;
import java.sql.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class BusinessOutboxServiceImpl implements IBusinessOutboxService {

  @Resource
  private BusinessOutboxRepository businessOutboxRepository;

  @Resource
  private ObjectProvider<IOutboxRelayService> relay;

  private final CacheClient cache;

  public BusinessOutboxServiceImpl(CacheClient cache) {
    this.cache = cache;
  }

  @Override
  public void appendBusinessEvent(String kind, long aggregateId, Long userId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox must share the business write transaction");
    }
    businessOutboxRepository.insertEvent(kind, aggregateId, userId);
  }

  @Override
  public void appendMediaDeletionEvent(String path, long userId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Image deletion event requires a business transaction");
    }
    businessOutboxRepository.insertMediaDeletion(userId, userId, path);
  }

  @Override
  public void appendShopCacheInvalidation(long shopId) {
    appendBusinessEvent("SHOP_INVALIDATE", shopId, null);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              cache.invalidate("cache:shop:" + shopId);
            } catch (RuntimeException e) {
              log.warn("Post-commit invalidation failed; the outbox will retry", e);
            }
          }
        });
  }

  @Override
  public void appendOrderExpirationEvent(long orderId, Long userId, Timestamp deadline) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Order deadline event requires a business transaction");
    }
    businessOutboxRepository.insertDeadline(orderId, userId, deadline);
  }

  @Scheduled(fixedDelay = 1000)
  @Override
  public void relayOutboxBatch() {
    relay.getObject().relayOutboxBatch();
  }
}
