package com.hmdp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.ICacheCheckpointService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

class DebeziumCacheConsumerTest {
  private static final String UPDATE =
      """
      {"before":{"id":5,"name":"old"},"after":{"id":5,"name":"new"},"op":"u",
       "source":{"db":"hmdp","table":"tb_shop","file":"mysql-bin.000003","pos":4242,
                 "ts_ms":1700000000000}}\
      """;
  private static final String DDL =
      """
      {"source":{"db":"hmdp","table":"tb_voucher"},"databaseName":"hmdp",
       "ddl":"ALTER TABLE tb_voucher ADD COLUMN x INT","tableChanges":[]}\
      """;
  private static final String OTHER_DB =
      """
      {"after":{"id":7},"op":"c","source":{"db":"other","table":"tb_shop","file":"f","pos":1}}\
      """;

  private final CacheChangeProjector projector = mock(CacheChangeProjector.class);
  private final ICacheCheckpointService checkpoints = mock(ICacheCheckpointService.class);
  private final Acknowledgment ack = mock(Acknowledgment.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

  @SuppressWarnings("unchecked")
  private DebeziumCacheConsumer consumer() {
    when(projector.isRelevant("hmdp", "tb_shop")).thenReturn(true);
    when(projector.isRelevant("hmdp", "tb_voucher")).thenReturn(true);
    DebeziumCacheConsumer consumer =
        new DebeziumCacheConsumer(
            projector, checkpoints, mock(ConsumerFactory.class), new ObjectMapper(), metrics);
    ReflectionTestUtils.setField(consumer, "group", "hmdp-cache-cdc");
    ReflectionTestUtils.setField(consumer, "schema", "hmdp");
    ReflectionTestUtils.setField(consumer, "topicPrefix", "hmdp-cdc");
    return consumer;
  }

  private static ConsumerRecord<String, String> record(String topic, long offset, String value) {
    return new ConsumerRecord<>(topic, 0, offset, "k", value);
  }

  @Test
  void projectsRowsAndDdlThenCheckpointsBeforeCommitting() {
    DebeziumCacheConsumer consumer = consumer();
    consumer.processBatch(
        List.of(
            record("hmdp-cdc.hmdp.tb_shop", 10, UPDATE),
            record("hmdp-cdc", 3, DDL),
            record("hmdp-cdc.other.tb_shop", 11, OTHER_DB),
            record("hmdp-cdc.hmdp.tb_shop", 12, null)),
        ack);
    ArgumentCaptor<CdcRowChange> change = ArgumentCaptor.forClass(CdcRowChange.class);
    verify(projector).projectRowChange(change.capture());
    assertEquals(CdcRowChange.Operation.UPDATE, change.getValue().operation());
    assertEquals(Map.of("id", "5", "name", "new"), change.getValue().after());
    verify(projector).projectSchemaChange("hmdp", "tb_voucher");
    verify(checkpoints)
        .saveCheckpoint("hmdp-cache-cdc", 10L, "mysql-bin.000003", 4242L, 1700000000000L);
    verify(ack).acknowledge();
  }

  @Test
  void failedProjectionLeavesTheBatchUncommittedForReplay() {
    DebeziumCacheConsumer consumer = consumer();
    doThrow(new IllegalStateException("redis down")).when(projector).projectRowChange(any());
    assertThrows(
        IllegalStateException.class,
        () -> consumer.processBatch(List.of(record("hmdp-cdc.hmdp.tb_shop", 10, UPDATE)), ack));
    verify(ack, never()).acknowledge();
    verify(checkpoints, never()).saveCheckpoint(anyString(), any(), anyString(), any(), any());
    assertEquals(1.0, metrics.get("hmdp.cdc.batches").tag("result", "retry").counter().count());
  }

  @Test
  void topicsFollowDebeziumNaming() {
    assertEquals(
        List.of(
            "hmdp-cdc.hmdp.tb_shop",
            "hmdp-cdc.hmdp.tb_voucher",
            "hmdp-cdc.hmdp.tb_seckill_voucher",
            "hmdp-cdc"),
        List.of(consumer().topics()));
  }
}
