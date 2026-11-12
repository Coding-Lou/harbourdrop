package com.hmdp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * A shop category.
 *
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Entity
@Table(name = "tb_shop_type")
@DynamicInsert
@DynamicUpdate
public class ShopType implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Primary key */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Type name */
  private String name;

  /** Icon */
  private String icon;

  /** Sort order */
  private Integer sort;

  /** Created at */
  @JsonIgnore private LocalDateTime createTime;

  /** Updated at */
  @JsonIgnore private LocalDateTime updateTime;
}
