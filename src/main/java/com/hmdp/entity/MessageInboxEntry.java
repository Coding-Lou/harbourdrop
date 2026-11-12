package com.hmdp.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Consumer-side de-duplication record. Read-only anchor for its repository; writes are SQL. */
@Data
@Entity
@Immutable
@IdClass(MessageInboxEntry.Key.class)
@Table(name = "tb_message_inbox")
public class MessageInboxEntry {
  @Id private String consumerGroup;
  @Id private Long eventId;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {
    private String consumerGroup;
    private Long eventId;
  }
}
