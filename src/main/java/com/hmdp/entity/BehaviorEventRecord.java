package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

/**
 * A de-duplicated shop behavior event. Read-only anchor for its repository; writes are native SQL.
 */
@Data
@Entity
@Immutable
@Table(name = "tb_behavior_event")
public class BehaviorEventRecord {
  @Id private String eventId;
}
