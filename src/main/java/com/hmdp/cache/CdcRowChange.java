package com.hmdp.cache;

import java.util.Map;

/**
 * One row change captured from the MySQL binlog, independent of the CDC product that delivered it.
 *
 * <p>Column values are kept as strings; the cache projector only needs primary and foreign keys.
 */
public record CdcRowChange(
    String schema,
    String table,
    Operation operation,
    Map<String, String> before,
    Map<String, String> after) {

  public enum Operation {
    CREATE,
    UPDATE,
    DELETE,
    /** Snapshot read; treated like an update. */
    READ
  }

  public CdcRowChange {
    before = before == null ? Map.of() : Map.copyOf(before);
    after = after == null ? Map.of() : Map.copyOf(after);
  }
}
