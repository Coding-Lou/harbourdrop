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
 * 秒杀优惠券表，与优惠券是一对一关系
 *
 * @author 虎哥
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

  /** 关联的优惠券的id */
  @Id private Long voucherId;

  /** 库存 */
  private Integer stock;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 生效时间 */
  private LocalDateTime beginTime;

  /** 失效时间 */
  private LocalDateTime endTime;

  /** 更新时间 */
  private LocalDateTime updateTime;
}
