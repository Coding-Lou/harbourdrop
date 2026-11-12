package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSearchDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.repository.ShopRepository;
import com.hmdp.service.IBehaviorJournalService;
import com.hmdp.service.IBusinessOutboxService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ShopServiceImpl implements IShopService {
  private static final double EARTH_RADIUS_METERS = 6371000.0;

  @Resource
  private ShopRepository shopRepository;

  @Resource
  private IBehaviorJournalService behavior;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  @Resource
  private CacheClient cacheClient;

  @Resource
  private IBusinessOutboxService outbox;

  @Override
  public Result queryById(Long id) {
    // Guard against cache penetration
    Shop shop =
        cacheClient.queryWithPassThrough(
            CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

    // Mutex lock against cache breakdown
    // Shop shop = cacheClient
    //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL,
    // TimeUnit.MINUTES);

    // Logical expiry against cache breakdown
    // Shop shop = cacheClient
    //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L,
    // TimeUnit.SECONDS);

    // Course cache strategies share the same key. A logical-expiry wrapper cannot
    // be deserialized as a Shop by pass-through; repair malformed/mismatched entries.
    if (shop != null && !id.equals(shop.getId())) {
      cacheClient.invalidate(CACHE_SHOP_KEY + id);
      shop =
          cacheClient.queryWithMutex(
              CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
    if (shop == null) {
      return Result.fail("Shop not found.");
    }
    // 7. Return
    return Result.ok(shop);
  }

  @Override
  public Shop getById(Long id) {
    return id == null ? null : shopRepository.findById(id).orElse(null);
  }

  @Override
  public List<Shop> list() {
    return shopRepository.findAll();
  }

  private List<Shop> listByIds(Collection<Long> ids) {
    return shopRepository.findAllById(ids);
  }

  private static PageRequest pageOf(Integer current, int size) {
    return PageRequest.of(Math.max(1, current == null ? 1 : current) - 1, size);
  }

  @Override
  @Transactional
  public Result update(Shop shop) {
    Long id = shop.getId();
    if (id == null) {
      return Result.fail("Shop ID is required.");
    }
    // 1. Update the database
    if (!shopRepository.updateById(shop)) {
      return Result.fail("Shop not found or unchanged.");
    }
    // Durable invalidation event commits with the update; immediate deletion runs after commit.
    outbox.appendShopCacheInvalidation(id);
    return Result.ok();
  }

  @Override
  public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
    // 1. Check whether a coordinate query is needed
    if (x == null || y == null) {
      // No coordinate query needed; query the database
      Page<Shop> page =
          shopRepository.findAll(
              (shop, query, cb) -> cb.equal(shop.get("typeId"), typeId.longValue()),
              pageOf(current, SystemConstants.DEFAULT_PAGE_SIZE));
      // Return the data
      return Result.ok(page.getContent());
    }

    // 2. Compute paging parameters
    int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
    int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

    // 3. Query Redis, sort by distance and paginate. Result: shopId, distance
    String key = SHOP_GEO_KEY + typeId;
    // A fresh Redis has no course GEO fixtures; the default page must still show nearby shops.
    if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
      ShopSearchDTO criteria = new ShopSearchDTO();
      criteria.setTypeId(typeId.longValue());
      criteria.setX(x);
      criteria.setY(y);
      criteria.setPage(current);
      criteria.setSize(SystemConstants.DEFAULT_PAGE_SIZE);
      criteria.setSort("distance");
      return searchShops(criteria);
    }
    GeoResults<RedisGeoCommands.GeoLocation<String>> results =
        stringRedisTemplate
            .opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
            .search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                    .includeDistance()
                    .sortAscending()
                    .limit(end));
    // 4. Extract the IDs
    if (results == null) {
      return Result.ok(Collections.emptyList());
    }
    List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
    if (list.size() <= from) {
      // No next page; stop
      return Result.ok(Collections.emptyList());
    }
    // 4.1. Take the from ~ end slice
    List<Long> ids = new ArrayList<>(list.size());
    Map<String, Distance> distanceMap = new HashMap<>(list.size());
    list.stream()
        .skip(from)
        .forEach(
            result -> {
              // 4.2. Get the shop ID
              String shopIdStr = result.getContent().getName();
              ids.add(Long.valueOf(shopIdStr));
              // 4.3. Get the distance
              Distance distance = result.getDistance();
              distanceMap.put(shopIdStr, distance);
            });
    // 5. Query shops by ID, preserving the distance order returned by GEO
    Map<Long, Shop> byId = new HashMap<>();
    for (Shop shop : listByIds(ids)) {
      byId.put(shop.getId(), shop);
    }
    List<Shop> shops = new ArrayList<>(ids.size());
    for (Long shopId : ids) {
      Shop shop = byId.get(shopId);
      if (shop != null) {
        shop.setDistance(distanceMap.get(shopId.toString()).getValue());
        shops.add(shop);
      }
    }
    // 6. Return
    return Result.ok(shops);
  }

  @Override
  public Result searchShops(ShopSearchDTO criteria) {
    if (criteria.getTypeId() == null || criteria.getTypeId() <= 0) {
      return Result.fail("typeId must be a positive integer.");
    }
    Integer page = criteria.getPage();
    Integer size = criteria.getSize();
    if (page == null || page < 1 || page > 100000 || size == null || size < 1 || size > 20) {
      return Result.fail("page must be 1-100000 and size must be 1-20.");
    }
    if (criteria.getMaxPrice() != null && criteria.getMaxPrice() < 0) {
      return Result.fail("maxPrice can't be negative.");
    }
    if (criteria.getMinScore() != null
        && (criteria.getMinScore() < 0 || criteria.getMinScore() > 50)) {
      return Result.fail("minScore must be 0-50.");
    }
    Double x = criteria.getX();
    Double y = criteria.getY();
    if ((x == null) != (y == null)) {
      return Result.fail("x and y must be provided together.");
    }
    if (x != null
        && (!Double.isFinite(x)
            || !Double.isFinite(y)
            || x < -180
            || x > 180
            || y < -90
            || y > 90)) {
      return Result.fail("Coordinates are out of range.");
    }
    String sort = StringUtils.hasText(criteria.getSort()) ? criteria.getSort() : "score";
    if (!"score".equals(sort) && !"price".equals(sort) && !"distance".equals(sort)) {
      return Result.fail("sort must be score, price or distance.");
    }
    if ("distance".equals(sort) && x == null) {
      return Result.fail("Sorting by distance requires x and y.");
    }

    Long typeId = criteria.getTypeId();
    String keyword =
        StringUtils.hasText(criteria.getKeyword()) ? criteria.getKeyword().trim() : null;
    String area = StringUtils.hasText(criteria.getArea()) ? criteria.getArea().trim() : null;
    Long maxPrice = criteria.getMaxPrice();
    Integer minScore = criteria.getMinScore();
    boolean hasVoucher = Boolean.TRUE.equals(criteria.getHasVoucher());
    String order = sort;
    Specification<Shop> spec =
        (shop, query, cb) -> {
          List<Predicate> where = new ArrayList<>();
          where.add(cb.equal(shop.get("typeId"), typeId));
          if (keyword != null) {
            where.add(cb.like(shop.get("name"), "%" + keyword + "%"));
          }
          if (area != null) {
            where.add(
                cb.or(
                    cb.like(shop.get("area"), "%" + area + "%"),
                    cb.like(shop.get("address"), "%" + area + "%")));
          }
          if (maxPrice != null) {
            where.add(cb.le(shop.get("avgPrice"), maxPrice));
          }
          if (minScore != null) {
            where.add(cb.ge(shop.get("score"), minScore));
          }
          if (hasVoucher) {
            where.add(cb.exists(orderableVoucherOf(shop, query, cb)));
          }
          List<Order> orders = new ArrayList<>();
          if (x != null) {
            // Narrow candidates with a lat/lon bounding box, then filter to 5 km by great-circle
            // distance; sorting and paging both happen in MySQL.
            final double radiusMeters = 5000.0;
            double latDelta = radiusMeters / 111320.0;
            double lonDelta =
                radiusMeters / (111320.0 * Math.max(0.01, Math.cos(Math.toRadians(y))));
            where.add(cb.between(shop.get("x"), x - lonDelta, x + lonDelta));
            where.add(cb.between(shop.get("y"), y - latDelta, y + latDelta));
            Expression<Double> distance = distanceExpression(shop, cb, x, y);
            where.add(cb.le(distance, radiusMeters));
            if ("distance".equals(order)) {
              orders.add(cb.asc(distance));
            } else if ("price".equals(order)) {
              orders.add(cb.asc(shop.get("avgPrice")));
            } else {
              orders.add(cb.desc(shop.get("score")));
            }
          } else if ("price".equals(order)) {
            orders.add(cb.asc(shop.get("avgPrice")));
            orders.add(cb.desc(shop.get("score")));
          } else {
            orders.add(cb.desc(shop.get("score")));
            orders.add(cb.asc(shop.get("avgPrice")));
          }
          orders.add(cb.asc(shop.get("id")));
          query.orderBy(orders);
          return cb.and(where.toArray(new Predicate[0]));
        };
    Page<Shop> result = shopRepository.findAll(spec, PageRequest.of(page - 1, size));
    if (x != null) {
      for (Shop shop : result.getContent()) {
        shop.setDistance(distanceMeters(x, y, shop.getX(), shop.getY()));
      }
    }
    return Result.ok(result.getContent(), result.getTotalElements());
  }

  /**
   * A usable voucher: active ordinary voucher, or a seckill voucher with stock inside its window.
   */
  private static Subquery<Long> orderableVoucherOf(
      Root<Shop> shop, CriteriaQuery<?> query, CriteriaBuilder cb) {
    Subquery<Long> vouchers = query.subquery(Long.class);
    Root<Voucher> voucher = vouchers.from(Voucher.class);
    Subquery<Long> activities = query.subquery(Long.class);
    Root<SeckillVoucher> activity = activities.from(SeckillVoucher.class);
    activities
        .select(activity.get("voucherId"))
        .where(
            cb.equal(activity.get("voucherId"), voucher.get("id")),
            cb.gt(activity.get("stock"), 0),
            cb.lessThanOrEqualTo(activity.<LocalDateTime>get("beginTime"), cb.localDateTime()),
            cb.greaterThanOrEqualTo(activity.<LocalDateTime>get("endTime"), cb.localDateTime()));
    return vouchers
        .select(voucher.get("id"))
        .where(
            cb.equal(voucher.get("shopId"), shop.get("id")),
            cb.equal(voucher.get("status"), 1),
            cb.or(
                cb.equal(voucher.get("type"), 0),
                cb.and(cb.equal(voucher.get("type"), 1), cb.exists(activities))));
  }

  /** Haversine distance in meters, evaluated by MySQL so filtering and paging stay in SQL. */
  private static Expression<Double> distanceExpression(
      Root<Shop> shop, CriteriaBuilder cb, double x, double y) {
    Expression<Double> halfLat =
        cb.quot(radians(cb, cb.diff(shop.<Double>get("y"), y)), 2.0).as(Double.class);
    Expression<Double> halfLon =
        cb.quot(radians(cb, cb.diff(shop.<Double>get("x"), x)), 2.0).as(Double.class);
    Expression<Double> a =
        cb.sum(
            power2(cb, fn(cb, "SIN", halfLat)),
            cb.prod(
                cb.prod(
                    fn(cb, "COS", radians(cb, cb.literal(y))),
                    fn(cb, "COS", radians(cb, shop.get("y")))),
                power2(cb, fn(cb, "SIN", halfLon))));
    Expression<Double> clamped = cb.function("LEAST", Double.class, cb.literal(1.0), a);
    return cb.prod(EARTH_RADIUS_METERS * 2, fn(cb, "ASIN", fn(cb, "SQRT", clamped)));
  }

  private static Expression<Double> fn(CriteriaBuilder cb, String name, Expression<?> arg) {
    return cb.function(name, Double.class, arg);
  }

  private static Expression<Double> radians(CriteriaBuilder cb, Expression<?> degrees) {
    return fn(cb, "RADIANS", degrees);
  }

  private static Expression<Double> power2(CriteriaBuilder cb, Expression<Double> base) {
    return cb.function("POW", Double.class, base, cb.literal(2.0));
  }

  private static double distanceMeters(double x1, double y1, double x2, double y2) {
    double lat1 = Math.toRadians(y1);
    double lat2 = Math.toRadians(y2);
    double deltaLat = lat2 - lat1;
    double deltaLon = Math.toRadians(x2 - x1);
    double a =
        Math.pow(Math.sin(deltaLat / 2), 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLon / 2), 2);
    return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  @Override
  public Result catalog(String keyword, Long typeId, Integer page, Integer size, String sort) {
    if (page < 1
        || page > 10000
        || size < 1
        || size > 20
        || keyword.length() > 50
        || (typeId != null && typeId <= 0)
        || !("score".equals(sort) || "price".equals(sort))) {
      return Result.fail("Invalid shop query parameters.");
    }
    String name = StringUtils.hasText(keyword) ? keyword.trim() : null;
    Specification<Shop> spec =
        (shop, query, cb) -> {
          List<Predicate> where = new ArrayList<>();
          if (typeId != null) {
            where.add(cb.equal(shop.get("typeId"), typeId));
          }
          if (name != null) {
            where.add(cb.like(shop.get("name"), "%" + name + "%"));
          }
          query.orderBy(
              "price".equals(sort) ? cb.asc(shop.get("avgPrice")) : cb.desc(shop.get("score")),
              cb.asc(shop.get("id")));
          return cb.and(where.toArray(new Predicate[0]));
        };
    Page<Shop> result = shopRepository.findAll(spec, PageRequest.of(page - 1, size));
    return Result.ok(result.getContent(), result.getTotalElements());
  }

  @Override
  public Result queryShopsByIds(List<Long> ids) {
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 40
        || ids.stream().anyMatch(id -> id == null || id <= 0)) {
      return Result.fail("ids must contain 1-40 positive integers.");
    }
    List<Long> uniqueIds = ids.stream().distinct().collect(Collectors.toList());
    return Result.ok(listByIds(uniqueIds));
  }

  @Override
  public Result listAreas() {
    List<String> areas =
        shopRepository.selectDistinctAreas().stream().sorted().collect(Collectors.toList());
    return Result.ok(areas);
  }

  @Override
  public Result saveShop(Shop shop) {
    // Write to the database
    shopRepository.insert(shop);
    // Return the shop ID
    return Result.ok(shop.getId());
  }

  @Override
  public Result queryShopByName(String name, Integer current) {
    // Page through shops by type
    Specification<Shop> byName =
        (shop, query, cb) ->
            StringUtils.hasText(name) ? cb.like(shop.get("name"), "%" + name + "%") : null;
    Page<Shop> page =
        shopRepository.findAll(byName, pageOf(current, SystemConstants.MAX_PAGE_SIZE));
    // Return the data
    return Result.ok(page.getContent());
  }

  @Override
  public Result queryShopDetail(Long id) {
    Result result = queryById(id);
    if (Boolean.TRUE.equals(result.getSuccess()) && result.getData() != null) {
      behavior.recordShopVisit(
          id, UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
    }
    return result;
  }
}
