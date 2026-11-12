package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.enums.OrderStatus;
import com.hmdp.enums.VoucherType;
import com.hmdp.repository.VoucherOrderRepository;
import com.hmdp.service.IOrderLifecycleService;
import com.hmdp.service.ISeckillService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.BeanMaps;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class VoucherOrderServiceImpl implements IVoucherOrderService {
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createTime"), Sort.Order.desc("id"));

  @Resource
  private VoucherOrderRepository voucherOrderRepository;

  @Override
  public VoucherOrder getById(Long orderId) {
    return orderId == null ? null : voucherOrderRepository.findById(orderId).orElse(null);
  }

  @Value("${citylens.payment.mock-enabled:false}")
  private boolean mockPaymentEnabled;

  @Resource
  private ISeckillVoucherService seckillVoucherService;

  @Resource
  private IVoucherService voucherService;

  @Resource
  private RedisIdWorker redisIdWorker;

  @Resource
  private RedissonClient redissonClient;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private TransactionTemplate transactionTemplate;

  @Resource
  private IOrderLifecycleService lifecycle;

  private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

  static {
    SECKILL_SCRIPT = new DefaultRedisScript<>();
    SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    SECKILL_SCRIPT.setResultType(Long.class);
  }

  @Resource
  private ISeckillService coordinator;

  @Override
  public void persistSeckill(VoucherOrder order) {
    createVoucherOrder(order);
  }

  protected void createVoucherOrder(VoucherOrder voucherOrder) {
    Long userId = voucherOrder.getUserId();
    Long voucherId = voucherOrder.getVoucherId();
    RLock lock = redissonClient.getLock("lock:order:" + userId);
    if (!lock.tryLock()) {
      throw new IllegalStateException("Order lock busy; keeping the message for retry");
    }
    try {
      transactionTemplate.execute(
          status -> {
            if (coordinator != null && !coordinator.lockReservationForFulfillment(voucherOrder)) {
              return null;
            }
            VoucherOrder existing = getById(voucherOrder.getId());
            if (existing != null) {
              if (!userId.equals(existing.getUserId())
                  || !voucherId.equals(existing.getVoucherId())) {
                throw new IllegalStateException("Order ID conflict");
              }
              if (coordinator != null) {
                coordinator.markReservationSuccessful(voucherOrder.getId());
              }
              return null;
            }
            if (voucherOrderRepository.countByUserIdAndVoucherId(userId, voucherId) > 0) {
              throw new IllegalStateException(
                  "Admission order ID mismatch; keeping the message for review");
            }
            boolean deducted = seckillVoucherService.deductStock(voucherId);
            if (!deducted) {
              throw new IllegalStateException("Insufficient stock; keeping the message for review");
            }
            Voucher voucher = voucherService.getById(voucherId);
            if (voucher == null) {
              throw new IllegalStateException("Item not found; keeping the message for review");
            }
            snapshot(voucherOrder, voucher);
            voucherOrder.setStatus(initialOrderStatus(voucher));
            // insert() flushes immediately: a failure throws here and rolls back the stock.
            voucherOrderRepository.insert(voucherOrder);
            if (coordinator != null) {
              coordinator.markReservationSuccessful(voucherOrder.getId());
            }
            return null;
          });
    } finally {
      lock.unlock();
    }
  }

  @Autowired(required = false)
  private MeterRegistry metrics;

  @Override
  public Result seckillVoucher(Long voucherId) {
    if (voucherId == null || voucherId <= 0) {
      return Result.fail("Invalid drop.");
    }
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    // 1. Run the Lua script
    long started = System.nanoTime();
    Long result;
    try {
      result =
          stringRedisTemplate.execute(
              SECKILL_SCRIPT,
              Arrays.asList(
                  "seckill:stock:" + voucherId,
                  "seckill:order:" + voucherId,
                  "stream.orders",
                  "seckill:activity:" + voucherId,
                  "seckill:requests:" + voucherId,
                  "seckill:request:" + orderId),
              voucherId.toString(),
              userId.toString(),
              String.valueOf(orderId));
    } catch (RuntimeException unavailable) {
      if (metrics != null) {
        metrics.counter("hmdp.seckill.admissions", "result", "transport_error").increment();
      }
      return Result.fail(
          "Order service is temporarily unavailable. Check your order status later.");
    } finally {
      if (metrics != null) {
        metrics
            .timer("hmdp.seckill.admission")
            .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      }
    }
    if (metrics != null) {
      metrics
          .counter("hmdp.seckill.admissions", "result", admissionMetricLabel(result))
          .increment();
    }
    if (result == null) {
      return Result.fail("Order service is temporarily unavailable.");
    }
    int admissionCode = result.intValue();
    // 2. Check whether the result is 0
    if (admissionCode != 0) {
      // 2.1. Non-zero means no purchase admission
      if (admissionCode == 2) {
        Object original =
            stringRedisTemplate
                .opsForHash()
                .get("seckill:requests:" + voucherId, userId.toString());
        if (original != null) {
          return Result.ok(Long.parseLong(original.toString()));
        }
        VoucherOrder existing =
            voucherOrderRepository.findFirstByUserIdAndVoucherIdOrderByCreateTimeDesc(
                userId, voucherId);
        if (existing != null) {
          return Result.ok(existing.getId());
        }
        return Result.fail("You have already claimed this drop.");
      }
      return Result.fail(
          admissionCode == 1
              ? "Out of stock."
              : admissionCode == 3
                  ? "This drop isn't open right now."
                  : "Something went wrong with this drop. Please try again later.");
    }
    // 3. Return the order ID
    return Result.ok(orderId);
  }

  @Override
  public Result orderOrdinaryVoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    RLock lock = redissonClient.getLock("lock:order:ordinary:" + userId);
    if (!lock.tryLock()) {
      return Result.fail("Your order is being processed. Check back shortly.");
    }
    try {
      return transactionTemplate.execute(
          tx -> {
            voucherOrderRepository.insertOrdinarySlot(userId, voucherId);
            voucherOrderRepository.lockOrdinarySlot(userId, voucherId);
            Voucher voucher = voucherService.getById(voucherId);
            if (voucher == null
                || voucher.getStatus() == null
                || voucher.getStatus() != 1
                || voucher.getType() == null
                || !VoucherType.ORDINARY.matches(voucher.getType())) {
              return Result.fail("Deal not found or no longer available.");
            }
            VoucherOrder existing =
                voucherOrderRepository
                    .findFirstByUserIdAndVoucherIdAndStatusNotOrderByCreateTimeDesc(
                        userId, voucherId, OrderStatus.CANCELLED.getCode());
            if (existing != null) {
              return Result.ok(existing.getId());
            }
            VoucherOrder order = new VoucherOrder();
            order.setId(redisIdWorker.nextId("order"));
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            snapshot(order, voucher);
            // Free deals are claimed directly; paid deals only create a pending-payment order
            // and must never pass as paid.
            order.setStatus(initialOrderStatus(voucher));
            voucherOrderRepository.insert(order);
            lifecycle.registerOrderCreatedEventAndDeadline(order.getId());
            return Result.ok(order.getId());
          });
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Result queryMyOrder(Long orderId) {
    VoucherOrder order = getById(orderId);
    if (order == null || !UserHolder.getUser().getId().equals(order.getUserId())) {
      return Result.fail("Order not found.");
    }
    Map<String, Object> detail = BeanMaps.toMap(order);
    Voucher voucher = voucherService.getById(order.getVoucherId());
    if (voucher != null) {
      detail.put(
          "voucherType",
          order.getVoucherTypeSnapshot() == null
              ? voucher.getType()
              : order.getVoucherTypeSnapshot());
      detail.put(
          "payValue",
          order.getPayValueSnapshot() == null
              ? voucher.getPayValue()
              : order.getPayValueSnapshot());
      detail.put("shopId", voucher.getShopId());
      detail.put(
          "voucherTitle",
          order.getTitleSnapshot() == null ? voucher.getTitle() : order.getTitleSnapshot());
    }
    return Result.ok(detail);
  }

  @Override
  public Result queryMyVoucherOrder(Long voucherId) {
    VoucherOrder order =
        voucherOrderRepository.findFirstByUserIdAndVoucherIdOrderByCreateTimeDesc(
            UserHolder.getUser().getId(), voucherId);
    return Result.ok(order);
  }

  @Override
  public Result queryMyOrders(Integer page, boolean heldOnly) {
    if (page == null || page < 1 || page > 10000) {
      return Result.fail("Invalid paging parameters.");
    }
    Long owner = UserHolder.getUser().getId();
    PageRequest request = PageRequest.of(page - 1, 20, NEWEST_FIRST);
    // The owner always comes from the authenticated session, never from request parameters.
    Page<VoucherOrder> result =
        heldOnly
            ? voucherOrderRepository.findByUserIdAndStatus(
                owner, OrderStatus.PAID.getCode(), request)
            : voucherOrderRepository.findByUserId(owner, request);
    Map<Long, Voucher> vouchers =
        result.getContent().isEmpty()
            ? Collections.emptyMap()
            : voucherService
                .listByIds(
                    result.getContent().stream()
                        .map(VoucherOrder::getVoucherId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Voucher::getId, v -> v));
    // Drops carry a pickup window; read it in one batch for the drops on this page.
    Set<Long> dropIds =
        vouchers.values().stream()
            .filter(v -> Objects.equals(v.getType(), 1))
            .map(Voucher::getId)
            .collect(Collectors.toSet());
    Map<Long, SeckillVoucher> drops =
        dropIds.isEmpty()
            ? Collections.emptyMap()
            : seckillVoucherService.listByIds(dropIds).stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, d -> d));
    List<Map<String, Object>> items = new ArrayList<>();
    for (VoucherOrder order : result.getContent()) {
      Voucher voucher = vouchers.get(order.getVoucherId());
      Map<String, Object> item = new HashMap<>();
      item.put("order_id", order.getId().toString());
      item.put("voucher_id", order.getVoucherId());
      item.put("status", order.getStatus());
      item.put("created_at", order.getCreateTime());
      if (voucher != null) {
        item.put("shop_id", voucher.getShopId());
        item.put(
            "title",
            order.getTitleSnapshot() == null ? voucher.getTitle() : order.getTitleSnapshot());
        item.put(
            "pay_cents",
            order.getPayValueSnapshot() == null
                ? voucher.getPayValue()
                : order.getPayValueSnapshot());
        item.put(
            "voucher_type",
            order.getVoucherTypeSnapshot() == null
                ? voucher.getType()
                : order.getVoucherTypeSnapshot());
        item.put(
            "rules",
            order.getRulesSnapshot() == null ? voucher.getRules() : order.getRulesSnapshot());
      }
      SeckillVoucher drop = drops.get(order.getVoucherId());
      if (drop != null) {
        item.put("pickup_begin_time", drop.getPickupBeginTime());
        item.put("pickup_end_time", drop.getPickupEndTime());
      }
      items.add(item);
    }
    return Result.ok(items, result.getTotalElements());
  }

  @Override
  public Result payMockOrder(Long orderId, Long expectedPayCents) {
    if (!mockPaymentEnabled) {
      return Result.fail("Local mock payment is disabled.");
    }
    return transactionTemplate.execute(
        tx -> {
          Long userId = UserHolder.getUser().getId();
          VoucherOrder order = getById(orderId);
          if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("Order not found.");
          }
          Voucher voucher = voucherService.getById(order.getVoucherId());
          Long payable =
              order.getPayValueSnapshot() == null
                  ? (voucher == null ? null : voucher.getPayValue())
                  : order.getPayValueSnapshot();
          if (payable == null || payable <= 0 || !payable.equals(expectedPayCents)) {
            return Result.fail("Payment amount doesn't match. Please refresh the order.");
          }
          if (OrderStatus.PAID.matches(order.getStatus())) {
            return Result.ok(orderId.toString());
          }
          if (!OrderStatus.UNPAID.matches(order.getStatus())) {
            return Result.fail("This order is not awaiting payment.");
          }
          boolean changed =
              voucherOrderRepository.markPaidIfUnpaidAndNotExpired(
                      orderId,
                      userId,
                      OrderStatus.UNPAID.getCode(),
                      OrderStatus.PAID.getCode(),
                      LocalDateTime.now())
                  > 0;
          if (!changed) {
            VoucherOrder latest = getById(orderId);
            if (latest == null
                || !userId.equals(latest.getUserId())
                || !OrderStatus.PAID.matches(latest.getStatus())) {
              return Result.fail("Order status has changed. Please refresh and try again.");
            }
          }
          if (changed) {
            lifecycle.registerOrderPaidEvent(orderId, userId);
          }
          return Result.ok(orderId.toString());
        });
  }

  @Override
  public Result cancelUnpaidOrdinaryOrder(Long orderId) {
    return transactionTemplate.execute(
        tx -> {
          Long userId = UserHolder.getUser().getId();
          VoucherOrder order = getById(orderId);
          if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("Order not found.");
          }
          Voucher voucher = voucherService.getById(order.getVoucherId());
          if (voucher == null
              || voucher.getType() == null
              || !VoucherType.ORDINARY.matches(voucher.getType())
              || voucher.getPayValue() == null
              || voucher.getPayValue() <= 0) {
            return Result.fail("Only unpaid deal orders can be cancelled.");
          }
          if (OrderStatus.CANCELLED.matches(order.getStatus())) {
            return Result.ok(orderId);
          }
          if (!OrderStatus.UNPAID.matches(order.getStatus())) {
            return Result.fail("This order is not awaiting payment.");
          }
          boolean changed =
              voucherOrderRepository.changeStatusIfCurrent(
                      orderId,
                      userId,
                      OrderStatus.UNPAID.getCode(),
                      OrderStatus.CANCELLED.getCode())
                  > 0;
          if (changed) {
            lifecycle.registerOrderClosedEvent(orderId, userId);
          }
          return changed
              ? Result.ok(orderId)
              : Result.fail("Order status has changed. Please refresh and try again.");
        });
  }

  private static int initialOrderStatus(Voucher voucher) {
    boolean freeVoucher = voucher.getPayValue() != null && voucher.getPayValue() == 0L;
    return freeVoucher ? OrderStatus.PAID.getCode() : OrderStatus.UNPAID.getCode();
  }

  private static String admissionMetricLabel(Long result) {
    if (result == null) {
      return "unavailable";
    }
    if (result == 0) {
      return "accepted";
    }
    if (result == 1) {
      return "sold_out";
    }
    if (result == 2) {
      return "duplicate";
    }
    return result == 3 ? "inactive" : "invalid";
  }

  private static void snapshot(VoucherOrder order, Voucher voucher) {
    order.setShopIdSnapshot(voucher.getShopId());
    order.setPayValueSnapshot(voucher.getPayValue());
    order.setTitleSnapshot(voucher.getTitle());
    order.setRulesSnapshot(voucher.getRules());
    order.setVoucherTypeSnapshot(voucher.getType());
  }
}
