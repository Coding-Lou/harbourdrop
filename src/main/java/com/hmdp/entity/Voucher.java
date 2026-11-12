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
 * @author 虎哥
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

  /** 主键 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 商铺id */
  private Long shopId;

  /** 代金券标题 */
  private String title;

  /** 副标题 */
  private String subTitle;

  /** 使用规则 */
  private String rules;

  /** 支付金额 */
  private Long payValue;

  /** 抵扣金额 */
  private Long actualValue;

  /** 优惠券类型 */
  private Integer type;

  /** 优惠券类型 */
  private Integer status;

  /** 库存 */
  @Transient private Integer stock;

  /** 生效时间 */
  @Transient private LocalDateTime beginTime;

  /** 失效时间 */
  @Transient private LocalDateTime endTime;

  /** 是否可下单，由点评服务按自身时钟计算；仅用于响应，不入库。 */
  @Transient private Boolean orderable;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;
}
