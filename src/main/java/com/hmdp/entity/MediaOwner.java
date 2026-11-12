package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

/**
 * Owner and lifecycle state of an uploaded image. Read-only anchor for its repository; writes are
 * native SQL.
 */
@Data
@Entity
@Immutable
@Table(name = "tb_media_owner")
public class MediaOwner {
  @Id private String path;
}
