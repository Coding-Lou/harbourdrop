package com.hmdp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.SeckillServiceImpl;
import com.hmdp.support.IntegrationTestBase;
import com.hmdp.utils.CacheClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end CDC: direct SQL on MySQL -> binlog -> Debezium (Kafka Connect) -> Kafka -> cache
 * invalidation and warm-up. Nothing in these tests goes through the service layer or the outbox.
 */
@SpringBootTest(
    properties = {"citylens.reliability.scheduling-enabled=false", "hmdp.cdc.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CacheCdcIntegrationTest extends IntegrationTestBase {
  private static final String CONNECTOR = "hmdp-cache-test";

  static final GenericContainer<?> CONNECT =
      new GenericContainer<>(DockerImageName.parse("quay.io/debezium/connect:3.7.1.Final"))
          .withNetwork(NETWORK)
          .withExposedPorts(8083)
          .withEnv("BOOTSTRAP_SERVERS", KAFKA_INTERNAL_BOOTSTRAP)
          .withEnv("GROUP_ID", "hmdp-connect-test")
          .withEnv("CONFIG_STORAGE_TOPIC", "test_connect_configs")
          .withEnv("OFFSET_STORAGE_TOPIC", "test_connect_offsets")
          .withEnv("STATUS_STORAGE_TOPIC", "test_connect_statuses")
          .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
          .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
          .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
          .withEnv("HEAP_OPTS", "-Xms256m -Xmx512m")
          .dependsOn(KAFKA, MYSQL)
          .waitingFor(
              Wait.forHttp("/connectors").forPort(8083).withStartupTimeout(Duration.ofMinutes(3)));

  static {
    CONNECT.start();
    registerConnector();
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CacheClient cache;
  @Autowired MeterRegistry metrics;
  @Autowired SeckillServiceImpl seckill;
  @Autowired DebeziumCacheConsumer consumer;

  private static void registerConnector() {
    String config =
        """
        {
          "connector.class": "io.debezium.connector.mysql.MySqlConnector",
          "tasks.max": "1",
          "database.hostname": "mysql",
          "database.port": "3306",
          "database.user": "%s",
          "database.password": "%s",
          "database.server.id": "5402",
          "topic.prefix": "hmdp-cdc",
          "database.include.list": "%s",
          "table.include.list": "%s\\\\.tb_shop,%s\\\\.tb_voucher,%s\\\\.tb_seckill_voucher",
          "snapshot.mode": "no_data",
          "include.schema.changes": "true",
          "tombstones.on.delete": "false",
          "schema.history.internal.kafka.bootstrap.servers": "%s",
          "schema.history.internal.kafka.topic": "hmdp-cdc.schema-history",
          "schema.history.internal.store.only.captured.tables.ddl": "true",
          "topic.creation.default.partitions": "1",
          "topic.creation.default.replication.factor": "1",
          "key.converter": "org.apache.kafka.connect.json.JsonConverter",
          "key.converter.schemas.enable": "false",
          "value.converter": "org.apache.kafka.connect.json.JsonConverter",
          "value.converter.schemas.enable": "false"
        }
        """
            .formatted(
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                DATABASE,
                DATABASE,
                DATABASE,
                DATABASE,
                KAFKA_INTERNAL_BOOTSTRAP);
    String base = "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083);
    HttpClient http = HttpClient.newHttpClient();
    try {
      HttpResponse<String> created =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/connectors/" + CONNECTOR + "/config"))
                  .header("Content-Type", "application/json")
                  .PUT(HttpRequest.BodyPublishers.ofString(config))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertTrue(created.statusCode() / 100 == 2, created.body());
      // Changes made before the binlog reader starts would be missed, so wait for RUNNING.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      while (System.nanoTime() < deadline) {
        String status =
            http.send(
                    HttpRequest.newBuilder(
                            URI.create(base + "/connectors/" + CONNECTOR + "/status"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .body();
        if (status.contains("\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"")) {
          return;
        }
        Thread.sleep(500);
      }
      fail("Debezium connector did not reach RUNNING");
    } catch (Exception e) {
      throw new IllegalStateException("Debezium connector registration failed", e);
    }
  }

  @BeforeEach
  void guard() throws Exception {
    assertEquals("hmdp_optimization_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
    cache.invalidate("cache:shop:900201");
    cache.invalidate("cache:voucher-meta:900202");
    jdbc.update("DELETE FROM tb_voucher WHERE id=900202");
    await(() -> metrics.get("hmdp.cdc.connected").gauge().value() == 1);
  }

  @Test
  void externalSqlInvalidatesAndWarmsCurrentValueInRedis() throws Exception {
    long id = 900201;
    String old = "cdc-old";
    String fresh = "cdc-new-" + System.nanoTime();

    jdbc.update(
        "INSERT INTO "
            + "tb_shop(id,name,type_id,images,area,address,x,y,"
            + "avg_price,sold,comments,score) VALUES(?, ?,1,'img','test','test',"
            + "1,1,1,1,1,50) ON DUPLICATE KEY UPDATE name=VALUES(name)",
        id,
        old);
    await(() -> old.equals(read(id).getName()));
    String before = version(id);
    jdbc.update(
        "UPDATE tb_shop SET name=? WHERE id=?",
        fresh,
        id); // Direct SQL bypasses service and Outbox.
    await(
        () ->
            !before.equals(version(id))
                && fresh.equals(read(id).getName())
                && redis.opsForValue().get("cache:shop:" + id) != null);
    // The durable checkpoint records the Debezium binlog position of the last applied batch.
    await(
        () ->
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_cache_cdc_checkpoint WHERE "
                        + "destination='hmdp-cache-cdc' AND binlog_offset>0 "
                        + "AND binlog_file LIKE 'mysql-bin.%'",
                    Long.class)
                > 0);
    CacheClient peer = new CacheClient(redis);
    try {
      Shop value =
          peer.queryWithMutex(
              "cache:shop:",
              id,
              Shop.class,
              key -> {
                fail("A new instance must hit the warmed Redis value");
                return null;
              },
              20L,
              TimeUnit.MINUTES);
      assertEquals(fresh, value.getName());
    } finally {
      peer.close();
    }
    jdbc.update("DELETE FROM tb_shop WHERE id=?", id);
    await(() -> read(id) == null);
    seckill.refreshMetrics();
    assertTrue(metrics.get("hmdp.seckill.lag").gauge().value() >= 0);
  }

  @Test
  void voucherMoveInvalidatesBothShopAggregates() throws Exception {
    long id = 900202;
    jdbc.update(
        "INSERT INTO "
            + "tb_voucher(id,shop_id,title,rules,pay_value,actual_value,type,status) "
            + "VALUES(?,1,'cdc-voucher','rules',0,100,0,1) ON DUPLICATE KEY UPDATE "
            + "shop_id=1",
        id);
    await(() -> redis.opsForValue().get("cache:voucher-meta:" + id) != null);
    String oldOwner = revision("cache:voucher-list:1");
    String newOwner = revision("cache:voucher-list:2");

    jdbc.update("UPDATE tb_voucher SET shop_id=2,title='moved' WHERE id=?", id);
    await(
        () ->
            !oldOwner.equals(revision("cache:voucher-list:1"))
                && !newOwner.equals(revision("cache:voucher-list:2")));
    await(
        () -> {
          String raw = redis.opsForValue().get("cache:voucher-meta:" + id);
          return raw != null && raw.contains("moved");
        });
  }

  @Test
  void restartedConsumerResumesFromCommittedOffset() throws Exception {
    consumer.stop();
    long id = 900203;
    String name = "while-stopped-" + System.nanoTime();
    // Committed while no consumer runs: the event must wait in Kafka, not be lost.
    jdbc.update(
        "INSERT INTO "
            + "tb_shop(id,name,type_id,images,area,address,x,y,"
            + "avg_price,sold,comments,score) VALUES(?, ?,1,'img','test','test',"
            + "1,1,1,1,1,50) ON DUPLICATE KEY UPDATE name=VALUES(name)",
        id,
        name);
    consumer.start();
    await(
        () -> {
          String value = redis.opsForValue().get("cache:shop:" + id);
          return value != null && value.contains(name);
        });
    jdbc.update("DELETE FROM tb_shop WHERE id=?", id);
  }

  private Shop read(long id) {
    return cache.queryWithMutex(
        "cache:shop:",
        id,
        Shop.class,
        key ->
            jdbc
                .query(
                    "SELECT id,name FROM tb_shop WHERE id=?",
                    (rs, row) -> new Shop().setId(rs.getLong(1)).setName(rs.getString(2)),
                    key)
                .stream()
                .findFirst()
                .orElse(null),
        20L,
        TimeUnit.MINUTES);
  }

  private String version(long id) {
    return revision("cache:shop:" + id);
  }

  private String revision(String key) {
    String raw = redis.opsForValue().get(key + ":version");
    return raw == null ? "0" : raw;
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    fail("CDC condition not reached within 60 seconds");
  }
}
