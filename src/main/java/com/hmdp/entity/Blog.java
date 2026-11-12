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
@Table(name = "tb_blog")
@DynamicInsert
@DynamicUpdate
public class Blog implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 主键 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 商户id */
  private Long shopId;

  /** 用户id */
  private Long userId;

  /** 用户图标 */
  @Transient private String icon;

  /** 用户姓名 */
  @Transient private String name;

  /** 是否点赞过了 */
  @Transient private Boolean isLike;

  /** 标题 */
  private String title;

  /** 探店的照片，最多9张，多张以","隔开 */
  private String images;

  /** 探店的文字描述 */
  private String content;

  /** 点赞数量 */
  private Integer liked;

  /** 评论数量 */
  private Integer comments;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;
}
