package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.BusinessOutboxTask;
import com.hmdp.enums.ReservationState;
import com.hmdp.repository.BehaviorRepository;
import com.hmdp.repository.BusinessOutboxRepository;
import com.hmdp.repository.MessageRepository;
import com.hmdp.repository.SeckillReservationRepository;
import com.hmdp.service.IOperationsService;
import com.hmdp.service.ISeckillService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class OperationsServiceImpl implements IOperationsService {

  @Resource
  private BehaviorRepository behaviorRepository;

  @Resource
  private BusinessOutboxRepository businessOutboxRepository;

  @Resource
  private MessageRepository messageRepository;

  @Resource
  private SeckillReservationRepository seckillReservationRepository;

  @Resource
  private ISeckillService coordinator;

  @Override
  public Result queryReservationsForReview() {
    return coordinator.queryReservationsForReview();
  }

  @Override
  public Result querySeckillDeadLetters() {
    return Result.ok(seckillReservationRepository.selectDeadLetters());
  }

  @Override
  public Result queryUnfinishedBusinessEvents() {
    return Result.ok(businessOutboxRepository.selectUnfinishedEvents());
  }

  @Override
  public Result retryBusinessEvent(long eventId) {
    BusinessOutboxTask task = businessOutboxRepository.selectReviewEvent(eventId);
    if (task != null && "SECKILL_FULFILL".equals(task.getKind())) {
      long orderId = task.getAggregateId();
      if (ReservationState.REVIEW.matches(coordinator.queryReservationState(orderId))) {
        return coordinator.retryReservation(orderId);
      }
    }
    return businessOutboxRepository.retryReviewEvent(eventId) > 0
        ? Result.ok()
        : Result.fail("Task is not awaiting review.");
  }

  @Override
  public Result queryMessageFailures() {
    return Result.ok(messageRepository.selectFailures());
  }

  @Override
  public Result queryUnsentAnalyticsEvents() {
    return Result.ok(behaviorRepository.selectUnsentEvents());
  }

  @Override
  public Result queryAnalyticsFailures() {
    return Result.ok(behaviorRepository.selectFailures());
  }

  @Override
  public Result retryAnalyticsEvent(String eventId) {
    return behaviorRepository.retryReviewEvent(eventId) > 0
        ? Result.ok()
        : Result.fail("Event is not awaiting review.");
  }

  @Override
  public Result queryInventoryReconciliationReport(long voucherId) {
    return coordinator.queryInventoryReconciliationReport(voucherId);
  }

  @Override
  public Result publishActivity(long voucherId) {
    return coordinator.publishActivity(voucherId);
  }

  @Override
  public Result recoverActivity(long voucherId) {
    return coordinator.recoverActivity(voucherId);
  }

  @Override
  public Result retryReservation(long orderId) {
    return coordinator.retryReservation(orderId);
  }

  @Override
  public Result markReservationFailedAndCompensateRedis(long orderId) {
    return coordinator.markReservationFailedAndCompensateRedis(orderId);
  }
}
