package com.hmdp.service;

import java.sql.Timestamp;

/** Writes business events and sends cache notifications after the transaction commits. */
public interface IBusinessOutboxService {
  void appendBusinessEvent(String kind, long aggregateId, Long userId);

  void appendMediaDeletionEvent(String path, long userId);

  void appendShopCacheInvalidation(long shopId);

  void appendOrderExpirationEvent(long orderId, Long userId, Timestamp deadline);

  void relayOutboxBatch();
}
