package com.hmdp.service.impl;

import com.hmdp.entity.BusinessOutboxTask;
import com.hmdp.enums.OutboxState;
import com.hmdp.messaging.BusinessEvent;
import com.hmdp.messaging.BusinessEventType;
import com.hmdp.messaging.DeliveryRetryPolicy;
import com.hmdp.messaging.ProjectionResult;
import com.hmdp.repository.BusinessOutboxRepository;
import com.hmdp.repository.MessageRepository;
import com.hmdp.repository.SeckillReservationRepository;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.service.IBusinessProjectionService;
import com.hmdp.utils.FailureMessages;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BusinessEventServiceImpl implements IBusinessEventService {

  @Resource
  private BusinessOutboxRepository businessOutboxRepository;

  @Resource
  private MessageRepository messageRepository;

  @Resource
  private SeckillReservationRepository seckillReservationRepository;

  private final TransactionTemplate transactions;
  private final IBusinessProjectionService projector;
  private final MeterRegistry metrics;

  public BusinessEventServiceImpl(
      TransactionTemplate transactions,
      IBusinessProjectionService projector,
      MeterRegistry metrics) {
    this.transactions = new TransactionTemplate(transactions.getTransactionManager());
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.projector = projector;
    this.metrics = metrics;
  }

  public static String resolveConsumerGroup(String kind) {
    return BusinessEventType.from(kind).getConsumerGroup();
  }

  @Override
  public void consume(BusinessEvent event, String group) {
    if (event.getSchemaVersion() != 1
        || event.getEventId() <= 0
        || event.getKind() == null
        || !group.equals(resolveConsumerGroup(event.getKind()))) {
      throw new IllegalArgumentException("Invalid event version, event ID or consumer group");
    }
    long started = System.nanoTime();
    try {
      transactions.execute(
          tx -> {
            consumeLockedTask(event, group);
            return null;
          });
    } finally {
      metrics
          .timer("hmdp.mq.consume", "group", group)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private void consumeLockedTask(BusinessEvent event, String group) {
    // Lock only the current event; business effects, the inbox and task completion still commit
    // in one transaction.
    BusinessOutboxTask task = businessOutboxRepository.selectEventForUpdate(event.getEventId());
    if (task == null) {
      throw new IllegalArgumentException("Message has no matching persisted event");
    }
    if (!event.getKind().equals(task.getKind())
        || event.getAggregateId() != task.getAggregateId()) {
      throw new IllegalArgumentException("Message content does not match the persisted event");
    }
    if (messageRepository.countInboxEvent(group, event.getEventId()) > 0) {
      businessOutboxRepository.markDuplicateDone(event.getEventId());
      metrics.counter("hmdp.mq.consumptions", "group", group, "result", "duplicate").increment();
      return;
    }
    if (OutboxState.REVIEW.matches(task.getState())) {
      throw new IllegalStateException("Event awaits manual review");
    }
    if (projector.project(task) == ProjectionResult.CONTINUE) {
      appendFeedContinuation(task);
    }
    messageRepository.insertInboxEvent(group, event.getEventId());
    businessOutboxRepository.markConsumed(event.getEventId());
    metrics.counter("hmdp.mq.consumptions", "group", group, "result", "success").increment();
  }

  private void appendFeedContinuation(BusinessOutboxTask task) {
    // The cursor advances with the current batch; later batches persist independently and keep the
    // original publish time.
    long cursor = businessOutboxRepository.selectCursor(task.getId());
    businessOutboxRepository.insertFeedContinuation(
        task.getKind(), task.getAggregateId(), task.getUserId(), cursor, task.getCreatedAt());
  }

  /**
   * Records a consumption failure and decides whether the broker may acknowledge the current
   * message.
   *
   * @return true if the task is already done or the failure has been durably quarantined; false if
   *     it still needs redelivery. Invalid payloads are quarantined by broker message identity, so
   *     their eventId cannot be used to mark another real task as REVIEW.
   */
  @Override
  public boolean recordConsumptionFailure(
      BusinessEvent event,
      String group,
      String brokerId,
      String payload,
      RuntimeException failure,
      int deliveries) {
    return Boolean.TRUE.equals(
        transactions.execute(
            tx -> {
              boolean valid = false;
              if (event != null && event.getEventId() > 0) {
                BusinessOutboxTask task =
                    businessOutboxRepository.selectEventForUpdate(event.getEventId());
                if (task != null) {
                  try {
                    valid =
                        event.getSchemaVersion() == 1
                            && group.equals(resolveConsumerGroup(event.getKind()))
                            && Objects.equals(event.getKind(), task.getKind())
                            && event.getAggregateId() == task.getAggregateId();
                  } catch (RuntimeException invalid) {
                    valid = false;
                  }
                  if (valid && OutboxState.DONE.matches(task.getState())) {
                    return true;
                  }
                  if (valid) {
                    businessOutboxRepository.recordConsumeFailure(
                        FailureMessages.summarize(failure), event.getEventId());
                  }
                }
              }
              if (valid && deliveries < DeliveryRetryPolicy.MAX_CONSUME_ATTEMPTS) {
                return false;
              }
              String key = valid ? "outbox:" + event.getEventId() : "broker:" + brokerId;
              messageRepository.insertFailure(
                  group,
                  key,
                  valid ? event.getEventId() : null,
                  brokerId,
                  payload,
                  FailureMessages.summarize(failure));
              if (valid) {
                businessOutboxRepository.markReview(event.getEventId());
              }
              if (valid && "SECKILL_FULFILL".equals(event.getKind())) {
                if (seckillReservationRepository.markProcessingForReview(
                        FailureMessages.summarize(failure), event.getAggregateId())
                    > 0) {
                  seckillReservationRepository.insertDeadLetter(
                      "mq:outbox:" + event.getEventId(),
                      event.getAggregateId(),
                      payload,
                      FailureMessages.summarize(failure));
                }
              }
              metrics
                  .counter("hmdp.mq.consumptions", "group", group, "result", "quarantined")
                  .increment();
              return true;
            }));
  }
}
