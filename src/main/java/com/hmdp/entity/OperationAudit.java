package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Immutable;

/**
 * Audit trail of administrative writes. Read-only anchor for its repository; writes are native SQL.
 */
@Data
@Entity
@Immutable
@Table(name = "tb_operation_audit")
public class OperationAudit {
  @Id private Long id;
}
