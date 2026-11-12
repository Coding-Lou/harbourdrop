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
 * A shop.
 *
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

  /** Primary key */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Shop name */
  private String name;

  /** Shop type ID */
  private Long typeId;

  /** Shop images, separated by ',' */
  private String images;

  /** Neighbourhood, e.g. Gastown */
  private String area;

  /** Address */
  private String address;

  /** Longitude */
  private Double x;

  /** Latitude */
  private Double y;

  /** Average price, as an integer */
  private Long avgPrice;

  /** Sales count */
  private Integer sold;

  /** Review count */
  private Integer comments;

  /** Rating from 1 to 5, stored multiplied by 10 to avoid decimals */
  private Integer score;

  /** Opening hours, e.g. 10:00-22:00 */
  private String openHours;

  /** Created at */
  private LocalDateTime createTime;

  /** Updated at */
  private LocalDateTime updateTime;

  @Transient private Double distance;
}
