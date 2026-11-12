package com.hmdp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hmdp.dto.Result;
import com.hmdp.service.ISeckillService;
import com.hmdp.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Regression: Spring Data returns read-only tuple maps for native rows. Services that enrich a row
 * (as they did with MyBatis' HashMap) must receive a mutable copy from the repository.
 */
@SpringBootTest(properties = "citylens.reliability.scheduling-enabled=false")
class RepositoryRowsIntegrationTest extends IntegrationTestBase {
  private static final long ORDER = 931001L;
  private static final long USER = 931002L;

  @Autowired JdbcTemplate jdbc;
  @Autowired ISeckillService seckill;
  @Autowired SeckillReservationRepository reservations;

  @Test
  void persistedReservationStatusCanBeEnrichedAndReturned() {
    assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
    jdbc.update("DELETE FROM tb_seckill_reservation WHERE order_id=?", ORDER);
    jdbc.update(
        "INSERT INTO tb_seckill_reservation(order_id,voucher_id,user_id,state) "
            + "VALUES(?,931003,?,'SUCCESS')",
        ORDER,
        USER);
    try {
      Map<String, Object> row = reservations.selectOwnedRequest(ORDER, USER).get(0);
      row.put("probe", "mutable");
      assertEquals("mutable", row.get("probe"));

      Result status = seckill.queryReservationStatus(ORDER, USER);
      assertTrue(status.getSuccess(), String.valueOf(status.getErrorMsg()));
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) status.getData();
      assertEquals(String.valueOf(ORDER), data.get("order_id"));
      assertEquals("SUCCESS", data.get("state"));
    } finally {
      jdbc.update("DELETE FROM tb_seckill_reservation WHERE order_id=?", ORDER);
    }
  }
}
