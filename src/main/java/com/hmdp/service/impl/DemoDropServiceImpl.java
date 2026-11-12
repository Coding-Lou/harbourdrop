package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.repository.VoucherRepository;
import com.hmdp.service.IDemoDropService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Publishes surplus-bag drops for a few seeded shops so a fresh demo always has something to claim.
 *
 * <p>Drops go through {@link IVoucherService#addSeckillVoucher}, the same path an admin uses, so
 * the outbox publishes each one to Redis before it can be claimed. A shop gets a new drop only when
 * it has none on sale or upcoming (sold-out drops count as finished). Disabled unless {@code
 * hmdp.demo.drops-enabled=true}; the check assumes a single application instance.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "hmdp.demo.drops-enabled", havingValue = "true")
public class DemoDropServiceImpl implements IDemoDropService {
  static final Duration SALE_WINDOW = Duration.ofHours(2);
  static final Duration PICKUP_WINDOW = Duration.ofMinutes(30);

  @Resource
  private IVoucherService voucherService;
  @Resource
  private IShopService shopService;
  @Resource
  private VoucherRepository voucherRepository;

  @Value("${hmdp.demo.drop-shop-ids:1,3,7,13,15}")
  private String dropShopIds;

  @Value("${hmdp.demo.drop-stock:8}")
  private int dropStock;

  @Scheduled(
      initialDelayString = "${hmdp.demo.drop-initial-delay-ms:15000}",
      fixedDelayString = "${hmdp.demo.drop-check-ms:900000}")
  public void scheduledCheck() {
    try {
      int created = ensureDrops();
      if (created > 0) {
        log.info("Published {} demo surplus-bag drop(s)", created);
      }
    } catch (RuntimeException e) {
      log.warn("Demo drop check failed; it will run again on the next tick", e);
    }
  }

  @Override
  public int ensureDrops() {
    List<Long> shopIds =
        Arrays.stream(dropShopIds.split(","))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .map(Long::valueOf)
            .toList();
    if (shopIds.isEmpty()) {
      return 0;
    }
    LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
    Set<Long> covered = new HashSet<>();
    for (Number id : voucherRepository.selectShopIdsWithOpenDrops(shopIds, now)) {
      covered.add(id.longValue());
    }
    int created = 0;
    for (Long shopId : shopIds) {
      if (covered.contains(shopId)) {
        continue;
      }
      Shop shop = shopService.getById(shopId);
      if (shop == null) {
        continue;
      }
      voucherService.addSeckillVoucher(dropFor(shop, now));
      created++;
    }
    return created;
  }

  private Voucher dropFor(Shop shop, LocalDateTime now) {
    BagStyle style = BagStyle.of(shop.getTypeId());
    LocalDateTime saleEnd = now.plus(SALE_WINDOW);
    Voucher drop = new Voucher();
    drop.setShopId(shop.getId());
    drop.setTitle(style.title);
    drop.setSubTitle(style.contents);
    drop.setRules(
        "Contents vary with what is left at closing\\n"
            + "Show your order at the counter during the pickup window\\n"
            + "Ask staff about allergens at pickup\\n"
            + "One bag per customer");
    drop.setPayValue(style.priceCents);
    drop.setActualValue(style.valueCents);
    drop.setType(1);
    drop.setStatus(1);
    drop.setStock(dropStock);
    drop.setBeginTime(now);
    drop.setEndTime(saleEnd);
    drop.setPickupBeginTime(saleEnd);
    drop.setPickupEndTime(saleEnd.plus(PICKUP_WINDOW));
    return drop;
  }

  /** Bag wording and pricing per seeded shop type (see tb_shop_type in hmdp.sql). */
  private enum BagStyle {
    BAKERY("Bakery surprise bag", "Today's unsold bread and pastries", 599L, 1800L),
    CAFE("Café surprise bag", "Pastries, sandwiches and treats from today", 499L, 1500L),
    SUSHI("Sushi surprise box", "Rolls and nigiri made today", 899L, 2700L),
    DIM_SUM("Dim sum surprise bag", "Buns and dumplings from today's service", 799L, 2400L),
    PIZZA("Pizza surprise bag", "Slices and garlic knots from today", 699L, 2100L),
    BRUNCH("Brunch surprise bag", "Baked goods and sides from this morning", 599L, 1800L),
    PLANT("Plant-based surprise bowl", "Bowls and salads made today", 699L, 2100L),
    DELI("Deli surprise bag", "Sandwiches, cheese and produce from today", 799L, 2400L);

    final String title;
    final String contents;
    final long priceCents;
    final long valueCents;

    BagStyle(String title, String contents, long priceCents, long valueCents) {
      this.title = title;
      this.contents = contents;
      this.priceCents = priceCents;
      this.valueCents = valueCents;
    }

    static BagStyle of(Long typeId) {
      int index = typeId == null ? 0 : (int) (typeId - 1);
      BagStyle[] styles = values();
      return index >= 0 && index < styles.length ? styles[index] : BAKERY;
    }
  }
}
