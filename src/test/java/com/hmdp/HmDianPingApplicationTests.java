package com.hmdp;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.support.IntegrationTestBase;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class HmDianPingApplicationTests extends IntegrationTestBase {

  @Resource
  private CacheClient cacheClient;

  @Resource
  private ShopServiceImpl shopService;

  @Resource
  private RedisIdWorker redisIdWorker;

  @Resource
  private StringRedisTemplate stringRedisTemplate;

  private ExecutorService es = Executors.newFixedThreadPool(500);

  @Test
  void testIdWorker() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(300);

    Runnable task =
        () -> {
          for (int i = 0; i < 100; i++) {
            long id = redisIdWorker.nextId("order");
            System.out.println("id = " + id);
          }
          latch.countDown();
        };
    long begin = System.currentTimeMillis();
    for (int i = 0; i < 300; i++) {
      es.submit(task);
    }
    latch.await();
    long end = System.currentTimeMillis();
    System.out.println("time = " + (end - begin));
  }

  @Test
  void testSaveShop() throws InterruptedException {
    Shop shop = shopService.getById(1L);
    cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
  }

  @Test
  void loadShopData() {
    // 1. Query shops
    List<Shop> list = shopService.list();
    // 2. Group shops by typeId; shops with the same typeId go into one collection
    Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
    // 3. Write to Redis in batches
    for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
      // 3.1. Get the type ID
      Long typeId = entry.getKey();
      String key = SHOP_GEO_KEY + typeId;
      // 3.2. Get the shops of this type
      List<Shop> value = entry.getValue();
      List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
      // 3.3. Write to Redis: GEOADD key longitude latitude member
      for (Shop shop : value) {
        // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()),
        // shop.getId().toString());
        locations.add(
            new RedisGeoCommands.GeoLocation<>(
                shop.getId().toString(), new Point(shop.getX(), shop.getY())));
      }
      stringRedisTemplate.opsForGeo().add(key, locations);
    }
  }

  @Test
  void testHyperLogLog() {
    String[] values = new String[1000];
    int j = 0;
    for (int i = 0; i < 1000000; i++) {
      j = i % 1000;
      values[j] = "user_" + i;
      if (j == 999) {
        // Send to Redis
        stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
      }
    }
    // Count
    Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
    System.out.println("count = " + count);
  }
}
