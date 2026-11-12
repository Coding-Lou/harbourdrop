package com.hmdp.service;

import com.hmdp.analytics.BehaviorEvent;
import java.util.List;
import java.util.Map;

/** Aggregates behavior events into shop popularity rankings. */
public interface IBehaviorAggregationService {
  void accept(BehaviorEvent event);

  List<Map<String, Object>> ranking(int minutes, int limit);
}
