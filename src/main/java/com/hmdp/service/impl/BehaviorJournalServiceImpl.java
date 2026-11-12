package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.analytics.BehaviorEvent;
import com.hmdp.analytics.KafkaBehaviorTransport;
import com.hmdp.entity.BusinessOutboxTask;
import com.hmdp.messaging.DeliveryRetryPolicy;
import com.hmdp.repository.BehaviorRepository;
import com.hmdp.repository.VoucherOrderRepository;
import com.hmdp.service.IBehaviorJournalService;
import com.hmdp.utils.FailureMessages;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BehaviorJournalServiceImpl implements IBehaviorJournalService {

  @Resource
  private BehaviorRepository behaviorRepository;

  @Resource
  private VoucherOrderRepository voucherOrderRepository;

  private final ObjectMapper json;
  private final TransactionTemplate transactions;
  private final ObjectProvider<KafkaBehaviorTransport> transport;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong review = new AtomicLong();

  @Value("${hmdp.analytics.enabled:false}")
  private boolean enabled;

  public BehaviorJournalServiceImpl(
      ObjectMapper json,
      TransactionTemplate transactions,
      ObjectProvider<KafkaBehaviorTransport> transport) {
    this.json = json;
    this.transactions = transactions;
    this.transport = transport;
  }

  @Autowired
  public void bindMetrics(MeterRegistry registry) {
    registry.gauge("hmdp.analytics.outbox.pending", pending);
    registry.gauge("hmdp.analytics.outbox.review", review);
  }

  /**
   * Builds an analytics event from the persisted order snapshot and writes it to the analytics
   * outbox within the current business transaction.
   *
   * <p>The event ID reuses the source task ID, so a replay never creates a new business identity;
   * the voucher's current price or ownership never replaces the historical snapshot.
   */
  @Override
  public void appendOrderBehaviorEvent(BusinessOutboxTask source) {
    if (!enabled) {
      return;
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Order analytics event requires a business transaction");
    }
    List<Map<String, Object>> rows =
        voucherOrderRepository.selectBehaviorSnapshot(source.getAggregateId());
    if (rows.isEmpty() || rows.get(0).get("shop_id_snapshot") == null) {
      return;
    }
    Map<String, Object> order = rows.get(0);
    BehaviorEvent event =
        new BehaviorEvent(
            "order:" + source.getId(),
            ((Number) order.get("shop_id_snapshot")).longValue(),
            ((Number) order.get("user_id")).longValue(),
            "ORDER_PAID".equals(source.getKind()) ? "PAID" : "ORDER",
            source.getCreatedAt().getTime());
    try {
      behaviorRepository.insertOutboxEvent(
          event.getEventId(), event.getShopId(), json.writeValueAsString(event));
    } catch (JsonProcessingException bad) {
      throw new IllegalArgumentException("Failed to encode order analytics event", bad);
    }
  }

  @Override
  public void recordShopVisit(long shopId, Long userId) {
    submitShopVisitEvent(shopId, userId);
  }

  /**
   * Submits a visit analytics event and returns its event ID for looking up the persisted result
   * later.
   *
   * <p>Visit events are delivered best-effort; a returned event ID only means collection started,
   * not that Kafka acknowledged it or that the database stored it.
   *
   * <p>Returns null when analytics is disabled.
   */
  @Override
  public String submitShopVisitEvent(long shopId, Long userId) {
    if (enabled) {
      String eventId = UUID.randomUUID().toString();
      transport
          .getObject()
          .publishVisitBestEffort(
              new BehaviorEvent(eventId, shopId, userId, "VISIT", System.currentTimeMillis()));
      return eventId;
    }
    return null;
  }

  @Scheduled(fixedDelay = 1000)
  /**
   * Claims and sends a batch of persisted analytics events; network sends happen after the claim
   * transaction commits.
   *
   * <p>SENT is written only after Kafka acknowledges; if the ack is lost, the retry reuses the
   * original event ID and the consumer deduplicates idempotently.
   */
  @Override
  public void relayAnalyticsOutboxBatch() {
    if (!enabled) {
      return;
    }
    String owner = UUID.randomUUID().toString();
    List<Map<String, Object>> rows =
        transactions.execute(
            tx -> {
              List<Map<String, Object>> batch = behaviorRepository.selectClaimableEvents();
              for (Map<String, Object> row : batch) {
                behaviorRepository.claimEvent(owner, (String) row.get("event_id"));
              }
              return batch;
            });
    for (Map<String, Object> row : rows) {
      try {
        if (behaviorRepository.renewLease((String) row.get("event_id"), owner) == 0) {
          continue;
        }
        transport
            .getObject()
            .publishAndAwaitAcknowledgement(
                json.readValue(row.get("payload").toString(), BehaviorEvent.class));
        behaviorRepository.markSent((String) row.get("event_id"), owner);
      } catch (Exception failure) {
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        behaviorRepository.recordSendFailure(
            attempts >= DeliveryRetryPolicy.MAX_SEND_ATTEMPTS ? "REVIEW" : "PENDING",
            attempts,
            FailureMessages.summarize(failure),
            DeliveryRetryPolicy.delaySeconds(attempts),
            (String) row.get("event_id"),
            owner);
      }
    }
  }

  @Scheduled(fixedDelay = 60000)
  @Override
  public void redriveMissingAnalyticsEvents() {
    if (!enabled) {
      return;
    }
    behaviorRepository.redriveMissingEvents();
  }

  @Scheduled(fixedDelay = 10000)
  @Override
  public void refreshMetrics() {
    if (!enabled) {
      return;
    }
    pending.set(behaviorRepository.countPendingEvents());
    review.set(behaviorRepository.countReviewEvents());
  }
}
