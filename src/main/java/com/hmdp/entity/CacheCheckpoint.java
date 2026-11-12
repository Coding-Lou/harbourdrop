package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

/**
 * Last applied CDC position per consumer. Read-only anchor for its repository; writes are native
 * SQL.
 */
@Data
@Entity
@Immutable
@Table(name = "tb_cache_cdc_checkpoint")
public class CacheCheckpoint {
  @Id private String destination;
}
