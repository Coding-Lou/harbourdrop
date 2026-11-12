package com.hmdp.entity;

import com.hmdp.repository.Rows;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.Data;
import org.hibernate.annotations.Immutable;

/**
 * 秒杀持久资格；订单、用户与券的身份必须在重放时保持一致。
 *
 * <p>Immutable entity used only to type the repository; read through {@link #fromRow}.
 */
@Data
@Entity
@Immutable
@Table(name = "tb_seckill_reservation")
public class SeckillReservation {
  @Id private Long orderId;
  private Long voucherId;
  private Long userId;
  private String state;
  private int attempts;
  private String reason;

  public static SeckillReservation fromRow(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    SeckillReservation reservation = new SeckillReservation();
    reservation.setOrderId(Rows.longOf(row, "order_id"));
    reservation.setVoucherId(Rows.longOf(row, "voucher_id"));
    reservation.setUserId(Rows.longOf(row, "user_id"));
    reservation.setState(Rows.strOf(row, "state"));
    Integer attempts = Rows.intOf(row, "attempts");
    reservation.setAttempts(attempts == null ? 0 : attempts);
    reservation.setReason(Rows.strOf(row, "reason"));
    return reservation;
  }
}
