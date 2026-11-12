package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * @author 虎哥
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_blog_comments")
@DynamicInsert
@DynamicUpdate
public class BlogComments implements Serializable {

  private static final long serialVersionUID = 1L;

  /** 主键 */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 用户id */
  private Long userId;

  /** 探店id */
  private Long blogId;

  /** 关联的1级评论id，如果是一级评论，则值为0 */
  private Long parentId;

  /** 回复的评论id */
  private Long answerId;

  /** 回复的内容 */
  private String content;

  /** 点赞数 */
  private Integer liked;

  /** 状态，0：正常，1：被举报，2：禁止查看 */
  private Boolean status;

  /** 创建时间 */
  private LocalDateTime createTime;

  /** 更新时间 */
  private LocalDateTime updateTime;
}
