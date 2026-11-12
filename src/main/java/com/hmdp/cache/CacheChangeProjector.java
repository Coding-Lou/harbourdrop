package com.hmdp.cache;

import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.CacheClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CacheChangeProjector {
  private static final Set<String> WATCHED_TABLES =
      Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList("tb_shop", "tb_voucher", "tb_seckill_voucher")));
  private static final long CDC_SHOP_TTL_MINUTES = 20;
  private static final long VOUCHER_METADATA_TTL_MINUTES = 10;

  private final CacheClient cache;
  private final ObjectProvider<IShopService> shopServiceProvider;
  private final ObjectProvider<IVoucherService> voucherServiceProvider;
  private final MeterRegistry metrics;

  @Value("${hmdp.cdc.schema:hmdp}")
  private String schema;

  public CacheChangeProjector(
      CacheClient cache,
      ObjectProvider<IShopService> shopServiceProvider,
      ObjectProvider<IVoucherService> voucherServiceProvider,
      MeterRegistry metrics) {
    this.cache = cache;
    this.shopServiceProvider = shopServiceProvider;
    this.voucherServiceProvider = voucherServiceProvider;
    this.metrics = metrics;
  }

  public boolean isRelevant(String changeSchema, String table) {
    return schema.equals(changeSchema) && WATCHED_TABLES.contains(table);
  }

  /**
   * 仅处理指定数据库和表的变更，失效相关元数据缓存并按需回源。
   *
   * <p>活动执行库存和购买资格由秒杀流程维护，CDC 不用数据库快照覆盖 Redis 执行状态。回源读取的是数据库当前值， 因此重放旧事件不会把旧内容写回缓存。
   */
  public void projectRowChange(CdcRowChange change) {
    log.debug("CDC row {}.{} op={}", change.schema(), change.table(), change.operation());
    if (!isRelevant(change.schema(), change.table())) {
      return;
    }
    Map<String, String> identity = change.after().isEmpty() ? change.before() : change.after();
    if ("tb_shop".equals(change.table())) {
      projectShopChange(parseRowId(identity, "id"), change.operation());
    } else if ("tb_voucher".equals(change.table())) {
      projectVoucherChange(
          parseRowId(identity, "id"), change.operation(), change.before(), change.after());
    }
    // 活动库存与窗口实时查库；CDC 不覆盖 Redis 的执行库存或购买资格。
    metrics.counter("hmdp.cdc.rows", "table", change.table()).increment();
  }

  /** A DDL statement may change how rows decode, so drop every cached entry of that table. */
  public void projectSchemaChange(String changeSchema, String table) {
    if (!isRelevant(changeSchema, table)) {
      return;
    }
    invalidateTableMetadata(table);
    metrics.counter("hmdp.cdc.ddl", "table", table).increment();
  }

  private void invalidateTableMetadata(String table) {
    if ("tb_shop".equals(table)) {
      cache.invalidatePrefix("cache:shop:");
    }
    if ("tb_voucher".equals(table)) {
      cache.invalidatePrefix("cache:voucher-meta:");
      cache.invalidatePrefix("cache:voucher-list:");
    }
  }

  private void projectShopChange(long shopId, CdcRowChange.Operation operation) {
    cache.invalidate("cache:shop:" + shopId);
    if (operation != CdcRowChange.Operation.DELETE) {
      cache.queryWithMutex(
          "cache:shop:",
          shopId,
          Shop.class,
          id -> shopServiceProvider.getObject().getById(id),
          CDC_SHOP_TTL_MINUTES,
          TimeUnit.MINUTES);
    }
  }

  private void projectVoucherChange(
      long voucherId,
      CdcRowChange.Operation operation,
      Map<String, String> before,
      Map<String, String> after) {
    cache.invalidate("cache:voucher-meta:" + voucherId);
    // 券换店后旧、新店铺都需要失效；这里只对当前行的关联店铺去重。
    Set<String> shopIds = new HashSet<>();
    shopIds.add(before.get("shop_id"));
    shopIds.add(after.get("shop_id"));
    shopIds.remove(null);
    for (String shopId : shopIds) {
      cache.invalidate("cache:voucher-list:" + Long.parseLong(shopId));
    }
    if (operation != CdcRowChange.Operation.DELETE) {
      cache.queryWithMutex(
          "cache:voucher-meta:",
          voucherId,
          Voucher.class,
          id -> voucherServiceProvider.getObject().getById(id),
          VOUCHER_METADATA_TTL_MINUTES,
          TimeUnit.MINUTES);
    }
  }

  private static long parseRowId(Map<String, String> row, String key) {
    String value = row.get(key);
    if (value == null) {
      throw new IllegalStateException("binlog 缺少完整主键: " + key);
    }
    return Long.parseLong(value);
  }
}
