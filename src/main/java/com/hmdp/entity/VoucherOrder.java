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
 * An order for a voucher.
 *
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_voucher_order")
@DynamicInsert
@DynamicUpdate
public class VoucherOrder implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Primary key */
  @Id private Long id;

  /** ID of the ordering user */
  private Long userId;

  /** ID of the purchased voucher */
  private Long voucherId;

  /**
   * Amount payable at creation, in cents; null on legacy orders, which fall back to the current
   * voucher price.
   */
  private Long payValueSnapshot;

  /** Voucher title at creation; later renames must not rewrite this snapshot. */
  private String titleSnapshot;

  /** Usage rules at creation. */
  private String rulesSnapshot;

  /** Voucher type at creation: 0 ordinary, 1 flash sale. */
  private Integer voucherTypeSnapshot;

  /** Payment deadline for ordinary unpaid orders; null for free or never-closed orders. */
  private LocalDateTime expireAt;

  /** Shop at creation, used to attribute order analytics. */
  private Long shopIdSnapshot;

  /** Payment method: 1 balance, 2 Alipay, 3 WeChat Pay */
  private Integer payType;

  /** Order status: 1 unpaid, 2 paid, 3 redeemed, 4 cancelled, 5 refunding, 6 refunded */
  private Integer status;

  /** Ordered at */
  private LocalDateTime createTime;

  /** Paid at */
  private LocalDateTime payTime;

  /** Redeemed at */
  private LocalDateTime useTime;

  /** Refunded at */
  private LocalDateTime refundTime;

  /** Updated at */
  private LocalDateTime updateTime;
}
