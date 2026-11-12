package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Extended user profile.
 *
 * @since 2021-12-24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_user_info")
@DynamicInsert
@DynamicUpdate
public class UserInfo implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Primary key; the user ID */
  @Id private Long userId;

  /** City name */
  private String city;

  /** Bio, at most 128 characters */
  private String introduce;

  /** Follower count */
  private Integer fans;

  /** Following count */
  private Integer followee;

  /** Gender: 0 male, 1 female */
  private Boolean gender;

  /** Birthday */
  private LocalDate birthday;

  /** Points */
  private Integer credits;

  /** Membership level 0-9; 0 means not a member */
  private Boolean level;

  /** Created at */
  private LocalDateTime createTime;

  /** Updated at */
  private LocalDateTime updateTime;
}
