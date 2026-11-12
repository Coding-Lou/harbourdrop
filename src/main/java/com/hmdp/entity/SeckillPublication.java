package com.hmdp.entity;

import com.hmdp.repository.Rows;
import java.sql.Timestamp;
import java.util.Map;
import lombok.Data;

/** Current stock, time window, and listing status needed to publish a campaign. */
@Data
public class SeckillPublication {
  private Long voucherId;
  private Integer stock;
  private Timestamp beginTime;
  private Timestamp endTime;
  private Integer status;

  public static SeckillPublication fromRow(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    SeckillPublication publication = new SeckillPublication();
    publication.setVoucherId(Rows.longOf(row, "voucher_id"));
    publication.setStock(Rows.intOf(row, "stock"));
    publication.setBeginTime(Rows.timestampOf(row, "begin_time"));
    publication.setEndTime(Rows.timestampOf(row, "end_time"));
    publication.setStatus(Rows.intOf(row, "status"));
    return publication;
  }
}
