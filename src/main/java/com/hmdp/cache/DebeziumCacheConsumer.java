package com.hmdp.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.ICacheCheckpointService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.BatchAcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Turns Debezium binlog events (published to Kafka by Kafka Connect) into cache invalidation.
 *
 * <p>Each poll is handled as one batch: every row is projected and the checkpoint is written before
 * the offsets are committed. Any failure leaves the batch uncommitted and the error handler seeks
 * back, so the whole batch is replayed. Projection re-reads current database state, which makes
 * replays safe. Kafka partition assignment replaces the old Redisson leader lock: one consumer in
 * the group owns each table topic.
 */
@Slf4j
@Component
public class DebeziumCacheConsumer {
  static final String[] TABLES = {"tb_shop", "tb_voucher", "tb_seckill_voucher"};

  private final CacheChangeProjector projector;
  private final ICacheCheckpointService checkpoints;
  private final ConsumerFactory<String, String> consumers;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final AtomicLong connected = new AtomicLong();
  private final AtomicLong lastPoll = new AtomicLong();
  private volatile ConcurrentMessageListenerContainer<String, String> container;

  @Value("${hmdp.cdc.enabled:false}")
  private boolean enabled;

  @Value("${hmdp.cdc.schema:hmdp}")
  private String schema;

  @Value("${hmdp.cdc.topic-prefix:hmdp-cdc}")
  private String topicPrefix;

  @Value("${hmdp.cdc.group:hmdp-cache-cdc}")
  private String group;

  public DebeziumCacheConsumer(
      CacheChangeProjector projector,
      ICacheCheckpointService checkpoints,
      ConsumerFactory<String, String> consumers,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.projector = projector;
    this.checkpoints = checkpoints;
    this.consumers = consumers;
    this.json = json;
    this.metrics = metrics;
    metrics.gauge("hmdp.cdc.connected", connected);
    metrics.gauge(
        "hmdp.cdc.poll.age.seconds",
        lastPoll,
        value ->
            value.get() == 0
                ? 0
                : Math.max(0, (System.currentTimeMillis() - value.get()) / 1000.0));
  }

  /** Data topics are {@code <prefix>.<schema>.<table>}; DDL goes to {@code <prefix>}. */
  String[] topics() {
    String[] topics = new String[TABLES.length + 1];
    for (int i = 0; i < TABLES.length; i++) {
      topics[i] = topicPrefix + "." + schema + "." + TABLES[i];
    }
    topics[TABLES.length] = topicPrefix;
    return topics;
  }

  @EventListener(ContextRefreshedEvent.class)
  public synchronized void start() {
    metrics.gauge("hmdp.cdc.enabled", enabled ? 1 : 0);
    if (!enabled || container != null) {
      return;
    }
    if (!schema.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("CDC schema 名称只允许字母、数字和下划线");
    }
    ContainerProperties properties = new ContainerProperties(topics());
    properties.setGroupId(group);
    properties.setAckMode(ContainerProperties.AckMode.MANUAL);
    properties.setIdleEventInterval(5000L);
    // Debezium creates topics lazily; refresh metadata often so new tables are picked up quickly.
    Properties overrides = new Properties();
    overrides.setProperty(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "5000");
    overrides.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "200");
    properties.setKafkaConsumerProperties(overrides);
    properties.setConsumerRebalanceListener(
        new ConsumerAwareRebalanceListener() {
          @Override
          public void onPartitionsAssigned(
              Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
            connected.set(1);
            lastPoll.set(System.currentTimeMillis());
          }

          @Override
          public void onPartitionsLost(
              Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
            connected.set(0);
          }
        });
    properties.setMessageListener(
        (BatchAcknowledgingMessageListener<String, String>) this::processBatch);
    ConcurrentMessageListenerContainer<String, String> created =
        new ConcurrentMessageListenerContainer<>(consumers, properties);
    ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
    backOff.setMaxInterval(10000);
    // Never skip a CDC batch: an unapplied invalidation would leave stale cache entries.
    created.setCommonErrorHandler(new DefaultErrorHandler(backOff));
    created.setApplicationEventPublisher(
        event -> {
          if (event instanceof ListenerContainerIdleEvent) {
            lastPoll.set(System.currentTimeMillis());
          }
        });
    created.setBeanName("hmdp-cache-cdc");
    created.start();
    container = created;
  }

  void processBatch(List<ConsumerRecord<String, String>> batch, Acknowledgment ack) {
    lastPoll.set(System.currentTimeMillis());
    try {
      boolean relevant = false;
      DebeziumEvents.SourcePosition tail = null;
      long tailOffset = -1;
      for (ConsumerRecord<String, String> record : batch) {
        if (record.value() == null) {
          continue; // tombstone
        }
        JsonNode event = json.readTree(record.value());
        if (DebeziumEvents.isSchemaChange(event)) {
          Optional<String[]> target = DebeziumEvents.schemaChangeTarget(event);
          if (target.isPresent() && projector.isRelevant(target.get()[0], target.get()[1])) {
            projector.projectSchemaChange(target.get()[0], target.get()[1]);
            relevant = true;
          }
          continue;
        }
        Optional<CdcRowChange> change = DebeziumEvents.rowChange(event);
        if (change.isEmpty()
            || !projector.isRelevant(change.get().schema(), change.get().table())) {
          continue;
        }
        projector.projectRowChange(change.get());
        relevant = true;
        Optional<DebeziumEvents.SourcePosition> position = DebeziumEvents.position(event);
        if (position.isPresent()) {
          tail = position.get();
          tailOffset = record.offset();
        }
      }
      if (tail != null) {
        checkpoints.saveCheckpoint(
            group, tailOffset, tail.binlogFile(), tail.binlogOffset(), tail.eventTimeMs());
        if (tail.eventTimeMs() > 0) {
          metrics
              .timer("hmdp.cdc.event.delay")
              .record(
                  Math.max(0, System.currentTimeMillis() - tail.eventTimeMs()),
                  TimeUnit.MILLISECONDS);
        }
      }
      ack.acknowledge();
      metrics.counter("hmdp.cdc.batches", "result", relevant ? "success" : "ignored").increment();
    } catch (Exception failure) {
      metrics.counter("hmdp.cdc.batches", "result", "retry").increment();
      throw new IllegalStateException("缓存 CDC 批次失败，保留位点重放", failure);
    }
  }

  @PreDestroy
  public synchronized void stop() {
    connected.set(0);
    if (container != null) {
      container.stop();
      container = null;
    }
  }
}
