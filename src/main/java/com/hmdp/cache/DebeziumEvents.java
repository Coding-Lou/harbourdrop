package com.hmdp.cache;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Parses Debezium MySQL change events serialized by the JSON converter with schemas disabled.
 *
 * <p>Row events look like {@code {"before":{..},"after":{..},"op":"u","source":{"db":..,"table":..,
 * "file":..,"pos":..,"ts_ms":..}}}; schema-change events carry {@code "ddl"} and {@code
 * "tableChanges"}.
 */
final class DebeziumEvents {
  private DebeziumEvents() {}

  /** Binlog coordinates of an event, used for the durable CDC checkpoint. */
  record SourcePosition(String binlogFile, long binlogOffset, long eventTimeMs) {}

  static boolean isSchemaChange(JsonNode event) {
    return event.has("ddl") || event.has("tableChanges");
  }

  static Optional<CdcRowChange> rowChange(JsonNode event) {
    CdcRowChange.Operation operation = operation(event.path("op").asText(""));
    if (operation == null) {
      return Optional.empty();
    }
    JsonNode source = event.path("source");
    return Optional.of(
        new CdcRowChange(
            source.path("db").asText(null),
            source.path("table").asText(null),
            operation,
            columns(event.get("before")),
            columns(event.get("after"))));
  }

  /** Schema and table touched by a DDL event, from {@code source} or the first table change. */
  static Optional<String[]> schemaChangeTarget(JsonNode event) {
    JsonNode source = event.path("source");
    String db = source.path("db").asText("");
    if (db.isEmpty()) {
      db = event.path("databaseName").asText("");
    }
    String table = source.path("table").asText("");
    if (table.isEmpty()) {
      JsonNode changes = event.path("tableChanges");
      if (changes.isArray() && !changes.isEmpty()) {
        // Debezium quotes identifiers: "\"hmdp\".\"tb_shop\"".
        String[] parts = changes.get(0).path("id").asText("").replace("\"", "").split("\\.");
        if (parts.length == 2) {
          db = db.isEmpty() ? parts[0] : db;
          table = parts[1];
        }
      }
    }
    if (db.isEmpty() || table.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new String[] {db, table});
  }

  static Optional<SourcePosition> position(JsonNode event) {
    JsonNode source = event.path("source");
    if (!source.hasNonNull("file") || !source.hasNonNull("pos")) {
      return Optional.empty();
    }
    return Optional.of(
        new SourcePosition(
            source.get("file").asText(),
            source.get("pos").asLong(),
            source.path("ts_ms").asLong(event.path("ts_ms").asLong(0))));
  }

  private static CdcRowChange.Operation operation(String code) {
    switch (code) {
      case "c":
        return CdcRowChange.Operation.CREATE;
      case "u":
        return CdcRowChange.Operation.UPDATE;
      case "d":
        return CdcRowChange.Operation.DELETE;
      case "r":
        return CdcRowChange.Operation.READ;
      default:
        return null;
    }
  }

  private static Map<String, String> columns(JsonNode row) {
    Map<String, String> values = new HashMap<>();
    if (row == null || !row.isObject()) {
      return values;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (!field.getValue().isNull()) {
        values.put(field.getKey(), field.getValue().asText());
      }
    }
    return values;
  }
}
