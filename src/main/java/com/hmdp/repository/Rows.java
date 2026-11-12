package com.hmdp.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed access to native-query rows. Hibernate returns JDBC-default Java types (Byte for TINYINT,
 * Timestamp for DATETIME, BigDecimal for SUM), so callers convert through here instead of casting.
 */
public final class Rows {
  private Rows() {}

  public static Long toLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof Boolean flag) {
      return flag ? 1L : 0L;
    }
    return Long.valueOf(value.toString());
  }

  public static Integer toInt(Object value) {
    Long number = toLong(value);
    return number == null ? null : Math.toIntExact(number);
  }

  public static String toStr(Object value) {
    return value == null ? null : value.toString();
  }

  public static Timestamp toTimestamp(Object value) {
    if (value == null || value instanceof Timestamp) {
      return (Timestamp) value;
    }
    if (value instanceof LocalDateTime time) {
      return Timestamp.valueOf(time);
    }
    if (value instanceof OffsetDateTime time) {
      return Timestamp.from(time.toInstant());
    }
    if (value instanceof Date date) {
      return new Timestamp(date.getTime());
    }
    throw new IllegalArgumentException("Not a timestamp: " + value.getClass().getName());
  }

  /**
   * Mutable copy of a query row. Spring Data returns read-only tuple maps, while callers (as with
   * MyBatis before) may enrich a row before returning it.
   */
  public static Map<String, Object> mutable(Map<String, Object> row) {
    return row == null ? null : new LinkedHashMap<>(row);
  }

  public static List<Map<String, Object>> mutable(List<Map<String, Object>> rows) {
    List<Map<String, Object>> copies = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      copies.add(mutable(row));
    }
    return copies;
  }

  public static Long longOf(Map<String, Object> row, String column) {
    return toLong(row.get(column));
  }

  public static Integer intOf(Map<String, Object> row, String column) {
    return toInt(row.get(column));
  }

  public static String strOf(Map<String, Object> row, String column) {
    return toStr(row.get(column));
  }

  public static Timestamp timestampOf(Map<String, Object> row, String column) {
    return toTimestamp(row.get(column));
  }
}
