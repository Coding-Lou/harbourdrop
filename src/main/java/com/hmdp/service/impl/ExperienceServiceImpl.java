package com.hmdp.service.impl;

import com.hmdp.cache.CacheReadTrace;
import com.hmdp.cache.RequestCacheScope;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.repository.BehaviorRepository;
import com.hmdp.repository.MessageRepository;
import com.hmdp.service.IBehaviorJournalService;
import com.hmdp.service.IExperienceService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExperienceServiceImpl implements IExperienceService {
  @Resource
  private IShopService shopService;

  @Resource
  private IBehaviorJournalService behaviorJournalService;

  @Resource
  private IVoucherOrderService voucherOrderService;

  @Resource
  private BehaviorRepository behaviorRepository;

  @Resource
  private MessageRepository messageRepository;

  @Resource
  private MeterRegistry metrics;

  @Value("${hmdp.experience.enabled:false}")
  private boolean enabled;

  @Value("${hmdp.cache.enabled:true}")
  private boolean cacheEnabled;

  @Value("${hmdp.analytics.enabled:false}")
  private boolean analyticsEnabled;

  @Value("${hmdp.mq.enabled:false}")
  private boolean mqEnabled;

  @Value("${hmdp.cdc.enabled:false}")
  private boolean cdcEnabled;

  @Override
  public Result queryExperienceCapabilities() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("enabled", enabled);
    if (enabled) {
      result.put("cacheEnabled", cacheEnabled);
      result.put("analyticsEnabled", analyticsEnabled);
      result.put("mqEnabled", mqEnabled);
      result.put("cdcEnabled", cdcEnabled);
      result.put("cdcConnected", readBooleanGauge("hmdp.cdc.connected"));
      result.put("mqClientStarted", readBooleanGauge("hmdp.mq.client.started"));
      result.put("kafkaConsumerRunning", readBooleanGauge("hmdp.kafka.consumer.running"));
      result.put("sampledAt", System.currentTimeMillis());
    }
    return Result.ok(result);
  }

  private Boolean readBooleanGauge(String name) {
    Gauge gauge = metrics.find(name).gauge();
    return gauge == null ? null : gauge.value() == 1;
  }

  /**
   * Reads the same shop twice within one request cache scope, recording the cache layers each read
   * passed through and its duration.
   *
   * <p>Used to observe request-level reuse; it never clears caches to fabricate a hit.
   */
  @Override
  public Result traceShopCacheReads(Long shopId) {
    if (!enabled) {
      return Result.fail("Demo inspection is disabled.");
    }
    if (shopId == null || shopId <= 0) {
      return Result.fail("Please choose a valid shop.");
    }
    List<Map<String, Object>> reads = new ArrayList<>();
    String shopName = null;
    try (RequestCacheScope scope = RequestCacheScope.open()) {
      for (int index = 0; index < 2; index++) {
        try (CacheReadTrace trace = CacheReadTrace.open()) {
          long started = System.nanoTime();
          Result loaded = shopService.queryById(shopId);
          if (!Boolean.TRUE.equals(loaded.getSuccess()) || loaded.getData() == null) {
            return loaded;
          }
          shopName = ((Shop) loaded.getData()).getName();
          Map<String, Object> read = new LinkedHashMap<>();
          read.put("sequence", index + 1);
          read.put("durationMicros", TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
          read.put("events", trace.snapshot());
          reads.add(read);
        }
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requestId", UUID.randomUUID().toString());
    result.put("shopId", shopId);
    result.put("shopName", shopName);
    result.put("reads", reads);
    return Result.ok(result);
  }

  /**
   * Submits three demo visit events and returns their event IDs so the browser can poll each
   * event's persisted result.
   *
   * <p>submitted is the number of events handed to collection, not the number Kafka acknowledged or
   * the database stored.
   */
  @Override
  public Result submitDemoVisitEvents(Long shopId) {
    if (!enabled || !analyticsEnabled) {
      return Result.fail("Kafka visit demo is disabled.");
    }
    if (shopId == null || shopId <= 0) {
      return Result.fail("Please choose a valid shop.");
    }
    Result shop = shopService.queryById(shopId);
    if (!Boolean.TRUE.equals(shop.getSuccess())) {
      return shop;
    }
    List<String> eventIds = new ArrayList<>();
    Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
    for (int index = 0; index < 3; index++) {
      String id = behaviorJournalService.submitShopVisitEvent(shopId, userId);
      if (id == null) {
        return Result.fail("Visit collection is disabled.");
      }
      eventIds.add(id);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("eventIds", eventIds);
    result.put("submitted", eventIds.size());
    result.put("shopId", shopId);
    return Result.ok(result);
  }

  @Override
  public Result queryVisitEventReceipts(List<String> eventIds) {
    if (!enabled || !analyticsEnabled) {
      return Result.fail("Kafka visit demo is disabled.");
    }
    if (eventIds == null || eventIds.isEmpty() || eventIds.size() > 3) {
      return Result.fail("Query 1 to 3 demo events at a time.");
    }
    for (String id : eventIds) {
      if (id == null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
        return Result.fail("Invalid demo event ID format.");
      }
    }
    List<Map<String, Object>> receipts = new ArrayList<>();
    for (String id : eventIds) {
      Map<String, Object> stored = behaviorRepository.selectEvent(id);
      Map<String, Object> receipt = new LinkedHashMap<>();
      receipt.put("eventId", id);
      receipt.put("state", stored == null ? "PENDING" : "PERSISTED");
      if (stored != null) {
        receipt.put("shopId", stored.get("shop_id"));
      }
      receipts.add(receipt);
    }
    return Result.ok(receipts);
  }

  @Override
  public Result queryMyOrderProgress(Long orderId) {
    if (!enabled) {
      return Result.fail("Demo inspection is disabled.");
    }
    if (orderId == null || orderId <= 0 || UserHolder.getUser() == null) {
      return Result.fail("Log in to view your orders.");
    }
    Result owned = voucherOrderService.queryMyOrder(orderId);
    if (!Boolean.TRUE.equals(owned.getSuccess())) {
      return owned;
    }
    return Result.ok(messageRepository.selectOrderProgress(orderId, UserHolder.getUser().getId()));
  }
}
