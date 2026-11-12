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
 * A user's post about a shop visit.
 *
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

  /** Primary key */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Shop ID */
  private Long shopId;

  /** User ID */
  private Long userId;

  /** User avatar */
  @Transient private String icon;

  /** User name */
  @Transient private String name;

  /** Whether the current user has liked it */
  @Transient private Boolean isLike;

  /** Title */
  private String title;

  /** Visit photos, up to 9, separated by "," */
  private String images;

  /** Text of the visit post */
  private String content;

  /** Like count */
  private Integer liked;

  /** Comment count */
  private Integer comments;

  /** Created at */
  private LocalDateTime createTime;

  /** Updated at */
  private LocalDateTime updateTime;
}
