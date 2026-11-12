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
 * A user account.
 *
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_user")
@DynamicInsert
@DynamicUpdate
public class User implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Primary key */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Phone number */
  private String phone;

  /** Login identity, stored lowercase. */
  private String email;

  /** Password, stored encrypted */
  private String password;

  /** Nickname; defaults to random characters */
  private String nickName;

  /** Avatar */
  private String icon = "";

  /** Created at */
  private LocalDateTime createTime;

  /** Updated at */
  private LocalDateTime updateTime;
}
