package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A voucher: an ordinary deal or a flash-sale drop.
 *
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_voucher")
@DynamicInsert
@DynamicUpdate
public class Voucher implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Primary key */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Shop ID */
  private Long shopId;

  /** Voucher title */
  private String title;

  /** Subtitle */
  private String subTitle;

  /** Usage rules */
  private String rules;

  /** Amount paid */
  private Long payValue;

  /** Amount deducted (face value) */
  private Long actualValue;

  /** Voucher type */
  private Integer type;

  /** Voucher status */
  private Integer status;

  /** Stock */
  @Transient private Integer stock;

  /** Sale opens at (drops only) */
  @Transient private LocalDateTime beginTime;

  /** Sale closes at (drops only) */
  @Transient private LocalDateTime endTime;

  /** Pickup window opens at (drops only, optional) */
  @Transient private LocalDateTime pickupBeginTime;

  /** Pickup window closes at (drops only, optional) */
  @Transient private LocalDateTime pickupEndTime;

  /**
   * Whether it can be ordered, computed by this service with its own clock; response only, not
   * persisted.
   */
  @Transient private Boolean orderable;

  /** Created at */
  private LocalDateTime createTime;

  /** Updated at */
  private LocalDateTime updateTime;
}
