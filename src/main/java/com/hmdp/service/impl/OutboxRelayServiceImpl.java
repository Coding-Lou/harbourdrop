package com.hmdp.service.impl;

import com.hmdp.entity.BusinessOutboxTask;
import com.hmdp.enums.OutboxState;
import com.hmdp.messaging.BusinessEvent;
import com.hmdp.messaging.DeliveryRetryPolicy;
import com.hmdp.messaging.EventPublisher;
import com.hmdp.repository.BusinessOutboxRepository;
import com.hmdp.repository.SeckillReservationRepository;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.service.IOutboxRelayService;
import com.hmdp.utils.FailureMessages;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class OutboxRelayServiceImpl implements IOutboxRelayService {

  @Resource
  private BusinessOutboxRepository businessOutboxRepository;

  @Resource
  private SeckillReservationRepository seckillReservationRepository;

  private final TransactionTemplate transactions;
  private final ObjectProvider<EventPublisher> publishers;
  private final IBusinessEventService consumer;
  private final MeterRegistry metrics;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicLong review = new AtomicLong();
  private final AtomicLong inflight = new AtomicLong();
  private final AtomicLong scheduled = new AtomicLong();

  @Value("${hmdp.mq.enabled:false}")
  private boolean mqEnabled;

  @Value("${hmdp.mq.lease-seconds:60}")
  private int leaseSeconds;

  @Value("${hmdp.mq.relay-batch-size:20}")
  private int batchSize;

  @Value("${hmdp.mq.redelivery-seconds:300}")
  private int redeliverySeconds;

  public OutboxRelayServiceImpl(
      TransactionTemplate transactions,
      ObjectProvider<EventPublisher> publishers,
      IBusinessEventService consumer,
      MeterRegistry metrics) {
    this.transactions = transactions;
    this.publishers = publishers;
    this.consumer = consumer;
    this.metrics = metrics;
    metrics.gauge("hmdp.outbox.pending", pending);
    metrics.gauge("hmdp.outbox.review", review);
    metrics.gauge("hmdp.outbox.inflight", inflight);
    metrics.gauge("hmdp.outbox.scheduled", scheduled);
  }

  /**
   * Claims a batch of deliverable tasks in a short transaction, writing the lease before
   * committing.
   *
   * <p>SKIP LOCKED lets other workers claim different tasks; network delivery happens after the
   * commit to avoid holding row locks for long.
   */
  @Override
  public List<BusinessOutboxTask> claimOutboxBatch(String leaseOwner) {
    return transactions.execute(
        tx -> {
          List<BusinessOutboxTask> tasks =
              businessOutboxRepository.selectClaimableEvents(Math.max(1, Math.min(100, batchSize)));
          for (BusinessOutboxTask task : tasks) {
            businessOutboxRepository.claimEvent(leaseOwner, effectiveLeaseSeconds(), task.getId());
          }
          return tasks;
        });
  }

  /**
   * Claims and delivers one batch of outbox tasks; it does not guarantee draining every pending
   * task in one call.
   *
   * <p>Each batch uses its own lease owner; on expiry or overlapping runs, the owner condition
   * keeps a newer worker's results from being overwritten.
   */
  @Override
  public void relayOutboxBatch() {
    // A per-batch owner stops overlapping calls in one process from overwriting each other once the
    // lease expires.
    String leaseOwner = UUID.randomUUID().toString();
    for (BusinessOutboxTask task : claimOutboxBatch(leaseOwner)) {
      sendClaimedTask(task, leaseOwner);
    }
  }

  private void sendClaimedTask(BusinessOutboxTask task, String leaseOwner) {
    long started = System.nanoTime();
    try {
      if (businessOutboxRepository.renewLease(effectiveLeaseSeconds(), task.getId(), leaseOwner)
          == 0) {
        return;
      }
      if (deliverOutboxTask(task, leaseOwner)) {
        metrics.counter("hmdp.mq.sends", "result", "success").increment();
      }
    } catch (RuntimeException failure) {
      recordSendFailure(task, leaseOwner, failure);
      metrics.counter("hmdp.mq.sends", "result", "retry").increment();
      log.warn(
          "Event {} delivery failed; retrying with the original event ID: {}",
          task.getId(),
          FailureMessages.summarize(failure));
    } finally {
      metrics.timer("hmdp.mq.send").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private boolean deliverOutboxTask(BusinessOutboxTask task, String leaseOwner) {
    BusinessEvent event = BusinessEvent.from(task);
    // Kafka has no per-message delay: a future deadline stays in the outbox (next_attempt_at =
    // deliver_at) and is published by the relay once due, so the database is the timer.
    if (event.getDeliverAt() != null && event.getDeliverAt() > System.currentTimeMillis()) {
      businessOutboxRepository.deferDeadline(task.getId(), leaseOwner);
      return false;
    }
    if (mqEnabled) {
      publishers.getObject().publish(event);
      // A fast consumer may already be done; the owner/state condition keeps DONE from being
      // overwritten with SENT.
      businessOutboxRepository.markSent(task.getId(), leaseOwner);
      return true;
    }
    consumer.consume(event, BusinessEventServiceImpl.resolveConsumerGroup(event.getKind()));
    return true;
  }

  private void recordSendFailure(
      BusinessOutboxTask task, String leaseOwner, RuntimeException failure) {
    int attempts = task.getAttempts() + 1;
    boolean needsReview = attempts >= DeliveryRetryPolicy.MAX_SEND_ATTEMPTS;
    String reason = FailureMessages.summarize(failure);
    transactions.execute(
        tx -> {
          int changed =
              businessOutboxRepository.recordSendFailure(
                  attempts,
                  DeliveryRetryPolicy.delaySeconds(attempts),
                  needsReview ? OutboxState.REVIEW.name() : OutboxState.PENDING.name(),
                  reason,
                  task.getId(),
                  leaseOwner);
          if (changed > 0 && needsReview && "SECKILL_FULFILL".equals(task.getKind())) {
            seckillReservationRepository.markProcessingForReview(reason, task.getAggregateId());
          }
          return null;
        });
  }

  private int effectiveLeaseSeconds() {
    return Math.max(10, leaseSeconds);
  }

  @Scheduled(fixedDelay = 60000)
  /**
   * Requeues events that were sent but remain unconsumed past the observation window, keeping the
   * original event ID.
   *
   * <p>Close events that are not yet due are not resent; duplicate consumption after a resend is
   * handled by the inbox and business idempotency rules.
   */
  @Override
  public void redriveUnconsumedEvents() {
    int age = Math.max(30, redeliverySeconds);
    // Future deadlines are expected to remain unconsumed. Redrive only after their due time.
    businessOutboxRepository.redriveUnconsumedEvents(-age, -age);
  }

  @Scheduled(fixedDelay = 10000)
  @Override
  public void refreshMetrics() {
    pending.set(businessOutboxRepository.countPendingEvents());
    review.set(businessOutboxRepository.countReviewEvents());
    inflight.set(businessOutboxRepository.countDueSentEvents());
    scheduled.set(businessOutboxRepository.countScheduledEvents());
  }
}
