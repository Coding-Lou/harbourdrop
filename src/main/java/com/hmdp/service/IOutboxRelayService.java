package com.hmdp.service;

import com.hmdp.entity.BusinessOutboxTask;
import java.util.List;

/**
 * Lease claiming and delivery of durable business tasks; network sends run after the claim
 * transaction commits.
 */
public interface IOutboxRelayService {
  List<BusinessOutboxTask> claimOutboxBatch(String leaseOwner);

  void relayOutboxBatch();

  void redriveUnconsumedEvents();

  void refreshMetrics();
}
