package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Flash-sale details for a voucher; one-to-one with the voucher table.
 *
 * @since 2022-01-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_seckill_voucher")
@DynamicInsert
@DynamicUpdate
public class SeckillVoucher implements Serializable {

  private static final long serialVersionUID = 1L;

  /** ID of the associated voucher */
  @Id private Long voucherId;

  /** Stock */
  private Integer stock;

  /** Created at */
  private LocalDateTime createTime;

  /** Sale opens at */
  private LocalDateTime beginTime;

  /** Sale closes at */
  private LocalDateTime endTime;

  /** Pickup window opens at (optional) */
  private LocalDateTime pickupBeginTime;

  /** Pickup window closes at (optional) */
  private LocalDateTime pickupEndTime;

  /** Updated at */
  private LocalDateTime updateTime;
}
