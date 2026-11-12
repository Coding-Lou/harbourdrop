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
@Table(name = "tb_shop")
@DynamicInsert
@DynamicUpdate
public class Shop implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 主键 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 商铺名称 */
  private String name;

  /** 商铺类型的id */
  private Long typeId;

  /** 商铺图片，多个图片以','隔开 */
  private String images;

  /** 商圈，例如陆家嘴 */
  private String area;

  /** 地址 */
  private String address;

  /** 经度 */
  private Double x;

  /** 维度 */
  private Double y;

  /** 均价，取整数 */
  private Long avgPrice;

  /** 销量 */
  private Integer sold;

  /** 评论数量 */
  private Integer comments;

  /** 评分，1~5分，乘10保存，避免小数 */
  private Integer score;

  /** 营业时间，例如 10:00-22:00 */
  private String openHours;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;

  @Transient private Double distance;
}
