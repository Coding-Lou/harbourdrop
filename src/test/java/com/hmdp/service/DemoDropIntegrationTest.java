package com.hmdp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.support.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/** Demo drops are published through the normal admin path and carry a pickup window. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "hmdp.demo.drops-enabled=true",
      "hmdp.demo.drop-shop-ids=13,15",
      "hmdp.mq.enabled=false",
      "citylens.reliability.scheduling-enabled=false"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoDropIntegrationTest extends IntegrationTestBase {
  private static final List<Long> SHOPS = List.of(13L, 15L);

  @Autowired private IDemoDropService drops;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TestRestTemplate http;
  @Autowired private ObjectMapper json;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  @AfterEach
  void removeDrops() {
    assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id FROM tb_voucher WHERE type=1 AND shop_id IN (13,15)", Long.class);
    for (Long id : ids) {
      jdbc.update("DELETE FROM tb_business_outbox WHERE aggregate_id=?", id);
      jdbc.update("DELETE FROM tb_seckill_activity WHERE voucher_id=?", id);
      jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", id);
      jdbc.update("DELETE FROM tb_voucher WHERE id=?", id);
      redis.delete("cache:voucher-meta:" + id);
    }
    for (Long shop : SHOPS) {
      redis.delete("cache:voucher-list:" + shop);
    }
  }

  @Test
  void publishesOneDropPerShopWithAPickupWindowAndDoesNotDuplicate() throws Exception {
    assertEquals(2, drops.ensureDrops());
    assertEquals(0, drops.ensureDrops());

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT sv.stock,sv.end_time,sv.pickup_begin_time,sv.pickup_end_time,v.pay_value"
                + " FROM tb_seckill_voucher sv JOIN tb_voucher v ON v.id=sv.voucher_id"
                + " WHERE v.shop_id=13 AND v.type=1");
    Timestamp saleEnd = (Timestamp) row.get("end_time");
    Timestamp pickupBegin = (Timestamp) row.get("pickup_begin_time");
    Timestamp pickupEnd = (Timestamp) row.get("pickup_end_time");
    assertEquals(saleEnd, pickupBegin);
    assertEquals(
        Duration.ofMinutes(30), Duration.between(pickupBegin.toInstant(), pickupEnd.toInstant()));
    assertEquals(8, ((Number) row.get("stock")).intValue());

    JsonNode list = json.readTree(http.getForObject("/voucher/list/13", String.class));
    assertTrue(list.path("success").asBoolean());
    JsonNode drop = null;
    for (JsonNode voucher : list.path("data")) {
      if (voucher.path("type").asInt() == 1) {
        drop = voucher;
      }
    }
    assertTrue(drop != null, list.toString());
    assertTrue(drop.path("orderable").asBoolean());
    assertFalse(drop.path("pickupBeginTime").isNull());
    assertFalse(drop.path("pickupEndTime").isNull());
  }

  @Test
  void soldOutDropIsReplacedButOpenOneIsKept() {
    assertEquals(2, drops.ensureDrops());
    jdbc.update(
        "UPDATE tb_seckill_voucher sv JOIN tb_voucher v ON v.id=sv.voucher_id"
            + " SET sv.stock=0 WHERE v.shop_id=13 AND v.type=1");
    assertEquals(1, drops.ensureDrops());
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tb_voucher WHERE type=1 AND shop_id IN (13,15)",
            Integer.class));
  }
}
