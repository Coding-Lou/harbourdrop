package com.hmdp.entity;

import com.hmdp.repository.Rows;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Timestamp;
import java.util.Map;
import lombok.Data;
import org.hibernate.annotations.Immutable;

/**
 * Database snapshot of a reliable business task; for internal processing, never returned as a
 * page response.
 *
 * <p>Mapped as an immutable entity only so it can type the repository; rows are always read as
 * plain values through {@link #fromRow} and every write is an explicit conditional SQL statement.
 */
@Data
@Entity
@Immutable
@Table(name = "tb_business_outbox")
public class BusinessOutboxTask {
  @Id private Long id;
  private String kind;
  private Long aggregateId;

  /** User who triggered the task; may be null for unowned tasks such as campaign retries. */
  private Long userId;

  /**
   * ID of the last follow row the feed has processed, used to query the next batch; not a
   * follower's user ID.
   */
  private long cursorId;

  private String state;
  private int attempts;
  private int consumeAttempts;

  /** Used only by media deletion tasks; null for other events. */
  private String resourcePath;

  /**
   * When the event occurred; feed continuations keep the original value so the sort time doesn't
   * change across retries.
   */
  private Timestamp createdAt;

  /** Target execution time for delayed events; null for ordinary events. */
  private Timestamp deliverAt;

  /** Builds a task from a native-query row; columns that were not selected stay empty. */
  public static BusinessOutboxTask fromRow(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    BusinessOutboxTask task = new BusinessOutboxTask();
    task.setId(Rows.longOf(row, "id"));
    task.setKind(Rows.strOf(row, "kind"));
    task.setAggregateId(Rows.longOf(row, "aggregate_id"));
    task.setUserId(Rows.longOf(row, "user_id"));
    Long cursor = Rows.longOf(row, "cursor_id");
    task.setCursorId(cursor == null ? 0 : cursor);
    task.setState(Rows.strOf(row, "state"));
    Integer attempts = Rows.intOf(row, "attempts");
    task.setAttempts(attempts == null ? 0 : attempts);
    Integer consumeAttempts = Rows.intOf(row, "consume_attempts");
    task.setConsumeAttempts(consumeAttempts == null ? 0 : consumeAttempts);
    task.setResourcePath(Rows.strOf(row, "resource_path"));
    task.setCreatedAt(Rows.timestampOf(row, "created_at"));
    task.setDeliverAt(Rows.timestampOf(row, "deliver_at"));
    return task;
  }
}
