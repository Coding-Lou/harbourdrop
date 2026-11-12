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
 * A comment on a post.
 *
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

  /** Primary key */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** User ID */
  private Long userId;

  /** Post ID */
  private Long blogId;

  /** ID of the parent top-level comment; 0 if this is a top-level comment */
  private Long parentId;

  /** ID of the comment being replied to */
  private Long answerId;

  /** Reply content */
  private String content;

  /** Like count */
  private Integer liked;

  /** Status: 0 normal, 1 reported, 2 hidden */
  private Boolean status;

  /** Created at */
  private LocalDateTime createTime;

  /** Updated at */
  private LocalDateTime updateTime;
}
