package com.hmdp.service;

import com.hmdp.entity.BusinessOutboxTask;

/** Records and delivers visit tracking events and durable order analytics events. */
public interface IBehaviorJournalService {
  void appendOrderBehaviorEvent(BusinessOutboxTask source);

  void recordShopVisit(long shopId, Long userId);

  /**
   * Returns the tracking event ID, which doesn't mean it was sent or stored; null when tracking is
   * disabled.
   */
  String submitShopVisitEvent(long shopId, Long userId);

  void relayAnalyticsOutboxBatch();

  void redriveMissingAnalyticsEvents();

  void refreshMetrics();
}
