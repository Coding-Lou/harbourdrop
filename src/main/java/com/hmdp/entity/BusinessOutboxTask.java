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
 * 可靠业务任务的数据库快照；用于内部处理，不直接作为页面响应。
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

  /** 发起用户；活动重试等无用户归属的任务可以为空。 */
  private Long userId;

  /** Feed 已处理到的关注关系行 ID，用于下一批查询；不是粉丝用户 ID。 */
  private long cursorId;

  private String state;
  private int attempts;
  private int consumeAttempts;

  /** 仅媒体删除任务使用，其他事件可以为空。 */
  private String resourcePath;

  /** 事件发生时间；Feed 续传沿用原值，防止排序时间随重试改变。 */
  private Timestamp createdAt;

  /** 延迟事件的目标执行时间；普通事件为空。 */
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
