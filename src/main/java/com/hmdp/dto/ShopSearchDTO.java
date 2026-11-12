package com.hmdp.dto;

import lombok.Data;

@Data
public class ShopSearchDTO {
  private Long typeId;
  private String keyword;
  private String area;
  private Long maxPrice;

  /** Rating on the database scale: pass 45 for 4.5. */
  private Integer minScore;

  /** Only return shops that currently have an available voucher. */
  private Boolean hasVoucher;

  private Double x;
  private Double y;
  private Integer page = 1;
  private Integer size = 10;

  /** score, price, or distance. */
  private String sort = "score";
}
