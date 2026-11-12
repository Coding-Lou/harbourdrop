package com.hmdp.service;

import com.hmdp.messaging.BusinessEvent;

/** Consumes business events and records consumption failures. */
public interface IBusinessEventService {
  void consume(BusinessEvent event, String group);

  boolean recordConsumptionFailure(
      BusinessEvent event,
      String group,
      String brokerId,
      String payload,
      RuntimeException failure,
      int deliveries);
}
