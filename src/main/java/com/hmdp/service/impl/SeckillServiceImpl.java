package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillPublication;
import com.hmdp.entity.SeckillReservation;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.enums.ReservationState;
import com.hmdp.reliability.RedisScripts;
import com.hmdp.repository.BusinessOutboxRepository;
import com.hmdp.repository.SeckillReservationRepository;
import com.hmdp.repository.SeckillVoucherRepository;
import com.hmdp.repository.VoucherOrderRepository;
import com.hmdp.service.ISeckillService;
import com.hmdp.service.IVoucherOrderService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
// Seckill initialization reads the campaign table, so it must wait for database migrations.
@DependsOn("flywayInitializer")
public class SeckillServiceImpl implements ISeckillService {

  public static final String STREAM = "stream.orders";
  public static final String GROUP = "g1";

  private static final DefaultRedisScript<Long> STREAM_LAG = RedisScripts.number("stream-lag.lua");
  private static final DefaultRedisScript<Long> COMPENSATE =
      RedisScripts.number("seckill-compensate.lua");

  @Resource
  private ObjectMapper objectMapper;

  @Resource
  private BusinessOutboxRepository businessOutboxRepository;

  @Resource
  private SeckillReservationRepository seckillReservationRepository;

  @Resource
  private SeckillVoucherRepository seckillVoucherRepository;

  @Resource
  private VoucherOrderRepository voucherOrderRepository;

  private final String consumer = "worker-" + UUID.randomUUID();
  private final StringRedisTemplate redis;
  private final TransactionTemplate transactions;
  private final RedissonClient redisson;
  private final ObjectProvider<IVoucherOrderService> orders;
  private final MeterRegistry metrics;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "seckill-consumer");
            t.setDaemon(true);
            return t;
          });
  private volatile boolean running;
  private String pendingCursor = "-";
  private final AtomicLong streamLag = new AtomicLong();
  private final AtomicLong reviewSize = new AtomicLong();
  private final AtomicLong pendingSize = new AtomicLong();

  @Value("${citylens.reliability.max-deliveries:5}")
  private int maxDeliveries;

  @Value("${citylens.reliability.claim-idle-ms:30000}")
  private long idleMillis;

  @Value("${citylens.reliability.scheduling-enabled:true}")
  private boolean enabled;

  @Value("${hmdp.mq.enabled:false}")
  private boolean mqEnabled;

  @Value("${hmdp.mq.seckill-enabled:false}")
  private boolean mqSeckill;

  public SeckillServiceImpl(
      StringRedisTemplate redis,
      TransactionTemplate transactions,
      RedissonClient redisson,
      ObjectProvider<IVoucherOrderService> orders,
      MeterRegistry metrics) {
    this.redis = redis;
    this.transactions = transactions;
    this.redisson = redisson;
    this.orders = orders;
    this.metrics = metrics;
    metrics.gauge("hmdp.seckill.pending", pendingSize);
    metrics.gauge("hmdp.seckill.lag", streamLag);
    metrics.gauge("hmdp.seckill.review", reviewSize);
  }

  @PostConstruct
  public void start() {
    redis.execute(
        (RedisCallback<Object>)
            connection -> {
              try {
                connection.execute(
                    "XGROUP",
                    bytes("CREATE"),
                    bytes(STREAM),
                    bytes(GROUP),
                    bytes("0"),
                    bytes("MKSTREAM"));
              } catch (RuntimeException e) {
                if (!hasMessage(e, "BUSYGROUP")) {
                  throw e;
                }
              }
              return null;
            });
    // Auto-publish only never-used activities; missing Redis state for a used activity fails
    // closed.
    for (Map<String, Object> row : seckillVoucherRepository.selectPublicationStates()) {
      long id = ((Number) row.get("voucher_id")).longValue();
      if (row.get("has_published") == null) {
        log.warn(
            "Campaign {} has no persisted publish baseline; keeping entry closed pending manual"
                + " review",
            id);
        continue;
      }
      if ("ACTIVE".equals(redis.opsForHash().get("seckill:activity:" + id, "state"))) {
        seckillVoucherRepository.markPublished(id);
        continue;
      }
      if (((Number) row.get("has_published")).intValue() == 0
          && !Boolean.TRUE.equals(redis.hasKey("seckill:activity:" + id))
          && seckillReservationRepository.countByVoucherId(id) == 0
          && Boolean.TRUE.equals(redis.opsForStream().size(STREAM) == 0)) {
        publishActivity(id);
      }
    }
    if (enabled) {
      running = true;
      executor.submit(this::consumeOrderStreamMessages);
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static boolean hasMessage(Throwable e, String text) {
    while (e != null) {
      if (e.getMessage() != null && e.getMessage().contains(text)) {
        return true;
      }
      e = e.getCause();
    }
    return false;
  }

  @PreDestroy
  public void stop() {
    running = false;
    executor.shutdownNow();
  }

  private void consumeOrderStreamMessages() {
    while (running && !Thread.currentThread().isInterrupted()) {
      try {
        List<MapRecord<String, Object, Object>> records =
            redis
                .opsForStream()
                .read(
                    Consumer.from(GROUP, consumer),
                    StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
                    StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        if (records != null) {
          for (MapRecord<String, Object, Object> record : records) {
            handleOrderStreamMessage(record);
          }
        }
        // XCLAIM transfers idle entries from any dead/slow consumer, not just this worker's
        // own PEL.
        reclaimPending();
        pendingSize.set(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages());
      } catch (RuntimeException e) {
        if (!running) {
          break;
        }
        log.warn("Seckill consumption failed temporarily; will retry", e);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Scheduled(fixedDelay = 10000)
  public void refreshMetrics() {
    pendingSize.set(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages());
    Long lag = redis.execute(STREAM_LAG, Collections.singletonList(STREAM), GROUP);
    streamLag.set(lag == null ? -1 : lag);
    reviewSize.set(seckillReservationRepository.countReviewRequests());
  }

  private void reclaimPending() {
    Range<String> range =
        "-".equals(pendingCursor) ? Range.unbounded() : Range.leftOpen(pendingCursor, "+");
    PendingMessages pending = redis.opsForStream().pending(STREAM, GROUP, range, 50);
    // Rotate even past entries which are not idle yet, so a young first page cannot starve the
    // tail.
    pendingCursor = "-";
    for (PendingMessage message : pending) {
      if (pending.size() == 50) {
        pendingCursor = message.getId().getValue();
      }
      long delay =
          idleMillis * Math.min(16, 1L << Math.min(4, (int) message.getTotalDeliveryCount() - 1));
      if (message.getElapsedTimeSinceLastDelivery().toMillis() < delay) {
        continue;
      }
      for (MapRecord<String, Object, Object> record :
          claim(consumer, Duration.ofMillis(delay), message.getId())) {
        handleOrderStreamMessage(record);
      }
    }
  }

  List<MapRecord<String, Object, Object>> claim(String worker, Duration idle, RecordId id) {
    List<ByteRecord> bytes =
        redis.execute(
            (RedisCallback<List<ByteRecord>>)
                connection ->
                    connection.streamCommands().xClaim(bytes(STREAM), GROUP, worker, idle, id));
    List<MapRecord<String, Object, Object>> records = new ArrayList<>();
    if (bytes != null) {
      for (ByteRecord record : bytes) {
        Map<Object, Object> values = new LinkedHashMap<>();
        record
            .getValue()
            .forEach(
                (key, value) ->
                    values.put(
                        new String(key, StandardCharsets.UTF_8),
                        new String(value, StandardCharsets.UTF_8)));
        records.add(MapRecord.create(STREAM, values).withId(record.getId()));
      }
    }
    return records;
  }

  private void handleOrderStreamMessage(MapRecord<String, Object, Object> record) {
    VoucherOrder order = null;
    boolean reservationMatched = false;
    long began = System.nanoTime();
    try {
      Map<Object, Object> value = record.getValue();
      order =
          new VoucherOrder()
              .setId(parsePositiveLongField(value, "id"))
              .setUserId(parsePositiveLongField(value, "userId"))
              .setVoucherId(parsePositiveLongField(value, "voucherId"));
      registerAndValidateReservation(order);
      reservationMatched = true;
      if (ReservationState.PROCESSING.matches(queryReservationState(order.getId()))) {
        if (mqEnabled && mqSeckill) {
          enqueueFulfillmentTask(order);
          // The durable outbox owns delivery now. Preserve the Lua PROCESSING display
          // state.
          redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
          return;
        } else {
          orders.getObject().persistSeckill(order);
        }
      }
      // Database commit precedes cache update and ACK. Duplicate delivery can safely repair
      // either.
      syncReservationStateAndAcknowledgeMessage(record, order.getId());
    } catch (InvalidOrderMessage e) {
      // A conflicting payload is isolated by message ID. It has no authority over the real
      // reservation.
      quarantine(record, order, e);
    } catch (RuntimeException e) {
      metrics.counter("hmdp.seckill.deliveries", "result", "retry").increment();
      if (reservationMatched) {
        seckillReservationRepository.recordAttempt(summarizeFailureReason(e), order.getId());
        SeckillReservation reservation =
            seckillReservationRepository.selectAttemptState(order.getId());
        if (reservation != null) {
          String state = reservation.getState();
          if (!ReservationState.PROCESSING.matches(state)) {
            syncReservationStateAndAcknowledgeMessage(record, order.getId());
          } else if (reservation.getAttempts() >= maxDeliveries) {
            final long id = order.getId();
            transactions.execute(
                status -> {
                  seckillReservationRepository.selectStateForUpdate(id);
                  if (seckillReservationRepository.markProcessingForReview(
                          summarizeFailureReason(e), id)
                      > 0) {
                    saveDeadLetter(record, id, e);
                  }
                  return null;
                });
            // Re-read after commit: another consumer may already have completed the
            // order.
            syncReservationStateAndAcknowledgeMessage(record, id);
          }
        }
      }
      // Registration/connection errors leave the message pending, without inventing a
      // business failure.
      log.warn("Order message {} failed: {}", record.getId(), summarizeFailureReason(e));
    } finally {
      metrics.timer("hmdp.seckill.consume").record(System.nanoTime() - began, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Syncs the persisted reservation status to the Redis display layer, then acknowledges the
   * current stream message.
   *
   * <p>An ACK means this message will not be retried; the status may be SUCCESS, FAILED or REVIEW,
   * so it does not mean the order succeeded.
   *
   * <p>If writing the display status fails, the message is not ACKed, so a later delivery can
   * repair the display from the database's current state.
   */
  private void syncReservationStateAndAcknowledgeMessage(
      MapRecord<String, Object, Object> record, long orderId) {
    String state = queryReservationState(orderId);
    redis.opsForHash().put("seckill:request:" + orderId, "state", state);
    redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
    metrics
        .counter("hmdp.seckill.deliveries", "result", state.toLowerCase(Locale.ROOT))
        .increment();
  }

  private void quarantine(
      MapRecord<String, Object, Object> record, VoucherOrder order, RuntimeException e) {
    transactions.execute(
        status -> {
          saveDeadLetter(record, order == null ? null : order.getId(), e);
          return null;
        });
    redis.opsForStream().acknowledge(STREAM, GROUP, record.getId());
    metrics.counter("hmdp.seckill.deliveries", "result", "invalid").increment();
    log.warn(
        "Quarantining conflicting or invalid message {}: {}",
        record.getId(),
        summarizeFailureReason(e));
  }

  private void saveDeadLetter(
      MapRecord<String, Object, Object> record, Long orderId, RuntimeException e) {
    seckillReservationRepository.insertDeadLetter(
        record.getId().getValue(), orderId, toJson(record.getValue()), summarizeFailureReason(e));
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return String.valueOf(value);
    }
  }

  private static class InvalidOrderMessage extends RuntimeException {
    InvalidOrderMessage(String message) {
      super(message);
    }
  }

  private static long parsePositiveLongField(Map<Object, Object> fields, String fieldName) {
    Object raw = fields.get(fieldName);
    if (raw == null) {
      throw new InvalidOrderMessage("Message is missing " + fieldName);
    }
    try {
      long parsed = Long.parseLong(raw.toString());
      if (parsed <= 0) {
        throw new InvalidOrderMessage("Invalid message parameter");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new InvalidOrderMessage("Invalid message parameter: " + fieldName);
    }
  }

  private static String summarizeFailureReason(Throwable e) {
    String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return text.substring(0, Math.min(500, text.length()));
  }

  /**
   * Records a durable fulfillment task in a transaction and hands further delivery to the outbox.
   *
   * <p>Callers must ACK the stream only after the transaction commits; otherwise a crash would lose
   * work that is not yet persisted.
   */
  private void enqueueFulfillmentTask(VoucherOrder order) {
    transactions.execute(
        tx -> {
          businessOutboxRepository.insertSeckillFulfillment(
              order.getId(), order.getUserId(), "seckill:" + order.getId());
          // An explicit reservation retry can re-publish the same quarantined event.
          businessOutboxRepository.retryFulfillment("seckill:" + order.getId());
          return null;
        });
    metrics.counter("hmdp.seckill.handoffs", "result", "durable").increment();
  }

  /**
   * Processes a fulfillment event against the persisted purchase admission; a repeated terminal
   * event does not deduct stock again.
   *
   * <p>Redis display updates run after the transaction commits; a display failure must not roll
   * back a persisted order or mark it failed.
   */
  @Override
  public void fulfillReservationFromMessage(long orderId) {
    SeckillReservation reservation = seckillReservationRepository.selectFulfillment(orderId);
    if (reservation == null) {
      throw new IllegalStateException("Missing persisted purchase admission");
    }
    String state = reservation.getState();
    if (ReservationState.SUCCESS.matches(state) || ReservationState.FAILED.matches(state)) {
      return;
    }
    if (!ReservationState.PROCESSING.matches(state)) {
      throw new IllegalStateException("Seckill request awaits manual review");
    }
    VoucherOrder order =
        new VoucherOrder()
            .setId(orderId)
            .setUserId(reservation.getUserId())
            .setVoucherId(reservation.getVoucherId());
    orders.getObject().persistSeckill(order);
    // Database status is authoritative. Redis is a best-effort display projection after commit.
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              redis
                  .opsForHash()
                  .put("seckill:request:" + orderId, "state", queryReservationState(orderId));
            } catch (RuntimeException unavailable) {
              log.warn(
                  "Redis display status for request {} needs repair; database fulfillment is"
                      + " committed",
                  orderId);
            }
          }
        });
  }

  /**
   * Records the purchase admission and checks that a duplicate order ID has the same user and
   * voucher.
   *
   * <p>INSERT IGNORE only prevents duplicate writes and cannot prove the message identity is
   * correct, so conflicting messages must be quarantined separately.
   */
  private void registerAndValidateReservation(VoucherOrder order) {
    seckillReservationRepository.insertReservation(
        order.getId(), order.getVoucherId(), order.getUserId());
    SeckillReservation reservation = seckillReservationRepository.selectReservation(order.getId());
    if (reservation == null
        || !reservation.getVoucherId().equals(order.getVoucherId())
        || !reservation.getUserId().equals(order.getUserId())) {
      throw new InvalidOrderMessage("Seckill admission or order ID conflict; needs review");
    }
  }

  /**
   * Locks the reservation row and decides whether fulfillment can continue; called by the order
   * transaction.
   *
   * @return true if still PROCESSING; false if it already succeeded or failed, so stock must not be
   *     deducted again. REVIEW throws and waits for manual handling; false does not mean "lock not
   *     acquired".
   */
  @Override
  public boolean lockReservationForFulfillment(VoucherOrder order) {
    SeckillReservation reservation =
        seckillReservationRepository.selectStateForUpdate(order.getId());
    if (reservation == null) {
      throw new IllegalStateException("Missing persisted purchase admission");
    }
    String state = reservation.getState();
    if (ReservationState.FAILED.matches(state) || ReservationState.SUCCESS.matches(state)) {
      return false;
    }
    if (!ReservationState.PROCESSING.matches(state)) {
      throw new IllegalStateException("Order awaits manual review");
    }
    return true;
  }

  /**
   * Records durable fulfillment success; called by the same transaction that deducts stock and
   * writes the order.
   *
   * <p>This method only updates the database; the Redis display status is synced after commit.
   */
  @Override
  public void markReservationSuccessful(long orderId) {
    seckillReservationRepository.markSuccess(orderId);
  }

  @Override
  public String queryReservationState(long orderId) {
    return seckillReservationRepository.selectState(orderId);
  }

  @Override
  public Result queryReservationStatus(long orderId, long userId) {
    List<Map<String, Object>> rows =
        seckillReservationRepository.selectOwnedRequest(orderId, userId);
    if (!rows.isEmpty()) {
      rows.get(0).put("order_id", String.valueOf(orderId));
      return Result.ok(rows.get(0));
    }
    Map<Object, Object> request = redis.opsForHash().entries("seckill:request:" + orderId);
    if (!String.valueOf(userId).equals(request.get("userId"))) {
      return Result.fail("Request not found.");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("order_id", String.valueOf(orderId));
    result.put("voucher_id", request.get("voucherId"));
    result.put("state", request.get("state"));
    return Result.ok(result);
  }

  /** Publishes a campaign for the first time; refuses to reset runtime stock once claims exist. */
  @Override
  public Result publishActivity(long voucherId) {
    return prepareActivityPublication(voucherId, PublicationMode.INITIAL);
  }

  /**
   * Recovery entry point after manual review; first confirms there is no in-flight work in the
   * shared stream or the persisted reservations.
   */
  @Override
  public Result recoverActivity(long voucherId) {
    return prepareActivityPublication(voucherId, PublicationMode.RECOVERY);
  }

  private Result prepareActivityPublication(long voucherId, PublicationMode mode) {
    boolean recovering = mode == PublicationMode.RECOVERY;
    RLock lock = redisson.getLock("lock:publish:" + voucherId);
    if (!lock.tryLock()) {
      return Result.fail("This drop is being prepared.");
    }
    try {
      SeckillPublication activity = seckillVoucherRepository.selectPublicationDetails(voucherId);
      if (activity == null) {
        return Result.fail("Drop not found.");
      }
      String activityKey = "seckill:activity:" + voucherId;
      Result admission = checkPublicationAdmission(voucherId, recovering, activityKey);
      if (admission != null) {
        return admission;
      }
      // Pause entry first, then read persisted facts and check in-flight messages; on failure stay
      // paused and never resume selling.
      redis.opsForHash().put(activityKey, "state", "PREPARING");
      if (recovering && hasUnfinishedActivityWork(voucherId)) {
        return Result.fail(
            "Some requests are in flight or awaiting review. The drop stays paused until they're"
                + " resolved.");
      }
      if (!recovering && seckillReservationRepository.countByVoucherId(voucherId) > 0) {
        return Result.fail("This drop already has claims. Use the review and recovery flow.");
      }
      rebuildBuyerIndex(voucherId);
      activatePublication(activity, activityKey);
      return Result.ok();
    } finally {
      lock.unlock();
    }
  }

  /** null means preparation may continue; non-null means it is done or publishing is refused. */
  private Result checkPublicationAdmission(long voucherId, boolean recovering, String activityKey) {
    if (recovering) {
      return null;
    }
    if ("ACTIVE".equals(redis.opsForHash().get(activityKey, "state"))) {
      return Result.ok();
    }
    if (seckillVoucherRepository.selectPublishedFlag(voucherId) == 1) {
      return Result.fail(
          "This published drop is missing its runtime state. Review and recover it first.");
    }
    long buyers =
        Optional.ofNullable(redis.opsForSet().size("seckill:order:" + voucherId)).orElse(0L);
    long requests =
        Optional.ofNullable(redis.opsForHash().size("seckill:requests:" + voucherId)).orElse(0L);
    if (buyers > 0 || requests > 0) {
      return Result.fail("This drop already has claim data. Use the review and recovery flow.");
    }
    return null;
  }

  private boolean hasUnfinishedActivityWork(long voucherId) {
    PendingMessages pending = redis.opsForStream().pending(STREAM, GROUP, Range.unbounded(), 1);
    // The stream is shared by all campaigns; rule out both unread and unacknowledged messages, not
    // just this campaign's reservations.
    boolean unreadMessages = hasUnreadStreamMessages();
    return !pending.isEmpty()
        || unreadMessages
        || seckillReservationRepository.countUnfinishedRequests(voucherId) > 0;
  }

  private boolean hasUnreadStreamMessages() {
    List<StreamInfo.XInfoGroup> groups =
        redis.opsForStream().groups(STREAM).stream().collect(Collectors.toList());
    List<MapRecord<String, Object, Object>> tail =
        redis.opsForStream().reverseRange(STREAM, Range.unbounded(), Limit.limit().count(1));
    if (tail.isEmpty()) {
      return false;
    }
    String tailId = tail.get(0).getId().getValue();
    return groups.stream()
        .anyMatch(
            group -> GROUP.equals(group.groupName()) && !tailId.equals(group.lastDeliveredId()));
  }

  private void rebuildBuyerIndex(long voucherId) {
    List<Map<String, Object>> buyers =
        seckillReservationRepository.selectSuccessfulBuyers(voucherId);
    redis.delete(Arrays.asList("seckill:order:" + voucherId, "seckill:requests:" + voucherId));
    for (Map<String, Object> buyer : buyers) {
      String userId = buyer.get("user_id").toString();
      redis.opsForSet().add("seckill:order:" + voucherId, userId);
      redis
          .opsForHash()
          .put("seckill:requests:" + voucherId, userId, buyer.get("order_id").toString());
    }
  }

  private void activatePublication(SeckillPublication activity, String activityKey) {
    redis
        .opsForValue()
        .set("seckill:stock:" + activity.getVoucherId(), activity.getStock().toString());
    Map<String, String> config = new HashMap<>();
    config.put("begin", String.valueOf(activity.getBeginTime().getTime()));
    config.put("end", String.valueOf(activity.getEndTime().getTime()));
    config.put("state", Integer.valueOf(1).equals(activity.getStatus()) ? "ACTIVE" : "PAUSED");
    seckillVoucherRepository.markPublished(activity.getVoucherId());
    redis.opsForHash().putAll(activityKey, config);
  }

  /**
   * Moves a reservation under review back to PROCESSING and records a retry task in the same
   * transaction.
   *
   * <p>The projection later puts it back into the stream; this call neither fulfills directly nor
   * acknowledges the original message early.
   */
  @Override
  public Result retryReservation(long orderId) {
    return transactions.execute(
        tx -> {
          SeckillReservation reservation =
              seckillReservationRepository.selectReservationForUpdate(orderId);
          if (reservation == null || !ReservationState.REVIEW.matches(reservation.getState())) {
            return Result.fail("Request is not awaiting review.");
          }
          seckillReservationRepository.resetForRetry(orderId);
          businessOutboxRepository.insertSeckillRetry(orderId);
          return Result.ok();
        });
  }

  /**
   * After a failure is confirmed manually, saves the FAILED status and tries to restore the stock
   * pre-deducted in Redis.
   *
   * <p>The database commits first and Redis then runs the Lua script; they are not one transaction,
   * so a failed compensation keeps the committed status.
   *
   * <p>The Lua script only releases the admission matching the current order ID, so repeated calls
   * never add stock twice or release a later order's admission.
   */
  @Override
  public Result markReservationFailedAndCompensateRedis(long orderId) {
    Result<SeckillReservation> result =
        transactions.execute(
            tx -> {
              SeckillReservation reservation =
                  seckillReservationRepository.selectReservationForUpdate(orderId);
              if (reservation == null) {
                return Result.fail("Request not found.");
              }
              if (!ReservationState.FAILED.matches(reservation.getState())
                  && !ReservationState.REVIEW.matches(reservation.getState())) {
                return Result.fail("Only quarantined failed requests can be handled.");
              }
              if (voucherOrderRepository.countByOrderId(orderId) > 0) {
                return Result.fail("The order is already saved, so stock can't be restored.");
              }
              seckillReservationRepository.markFailed(orderId);
              return Result.ok(reservation);
            });
    if (!Boolean.TRUE.equals(result.getSuccess())) {
      return result;
    }
    SeckillReservation reservation = result.getData();
    String voucherIdText = reservation.getVoucherId().toString();
    Long compensationResultCode =
        redis.execute(
            COMPENSATE,
            Arrays.asList(
                "seckill:stock:" + voucherIdText,
                "seckill:order:" + voucherIdText,
                "seckill:requests:" + voucherIdText,
                "seckill:request:" + orderId),
            reservation.getUserId().toString(),
            String.valueOf(orderId));
    if (compensationResultCode == null || compensationResultCode < 0) {
      return Result.fail(
          "Failure saved, but Redis stock wasn't restored. Retry, or review and recover.");
    }
    return Result.ok();
  }

  /**
   * Reads database stock, persisted orders, persisted reservations and Redis stock to build a
   * discrepancy report.
   *
   * <p>This method records metrics and never overwrites or repairs stock; discrepancies may also
   * include in-flight stream messages that are not yet recorded.
   */
  @Override
  public Result queryInventoryReconciliationReport(long voucherId) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("voucher_id", voucherId);
    List<Map<String, Object>> inventoryRows = seckillVoucherRepository.selectInventory(voucherId);
    if (inventoryRows.isEmpty()) {
      return Result.fail("Drop not found.");
    }
    long initialStock = ((Number) inventoryRows.get(0).get("total_stock")).longValue();
    long databaseRemainingStock = ((Number) inventoryRows.get(0).get("stock")).longValue();

    long persistedOrderCount = voucherOrderRepository.countByVoucherId(voucherId);
    long unresolvedReservationCount =
        seckillReservationRepository.countUnfinishedRequests(voucherId);
    String redisRemainingStock = redis.opsForValue().get("seckill:stock:" + voucherId);
    report.put("initial_stock", initialStock);
    report.put("database_remaining", databaseRemainingStock);
    report.put("persisted_orders", persistedOrderCount);
    report.put("database_consistent", initialStock == databaseRemainingStock + persistedOrderCount);
    report.put("unresolved_reservations", unresolvedReservationCount);
    report.put("redis_remaining", redisRemainingStock);
    report.put(
        "redis_difference",
        redisRemainingStock == null
            ? null
            : databaseRemainingStock
                - Long.parseLong(redisRemainingStock)
                - unresolvedReservationCount);
    report.put(
        "note",
        "Differences may include in-flight messages not yet recorded by consumers. This only"
            + " reports and never overwrites stock.");
    metrics
        .counter(
            "hmdp.seckill.reconciliations",
            "result",
            initialStock == databaseRemainingStock + persistedOrderCount
                ? "consistent"
                : "difference")
        .increment();
    return Result.ok(report);
  }

  @Scheduled(fixedDelay = 60000)
  public void checkActivityInventoryConsistency() {
    for (Long id : seckillVoucherRepository.selectActivityVoucherIds()) {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> report =
            (Map<String, Object>) queryInventoryReconciliationReport(id).getData();
        if (report != null && !Boolean.TRUE.equals(report.get("database_consistent"))) {
          log.warn("Seckill stock reconciliation mismatch: {}", report);
        }
      } catch (RuntimeException e) {
        log.warn("Seckill reconciliation temporarily unavailable: {}", id, e);
      }
    }
  }

  @Override
  public Result queryReservationsForReview() {
    return Result.ok(seckillReservationRepository.selectReviewRequests());
  }

  private enum PublicationMode {
    INITIAL,
    RECOVERY
  }
}
