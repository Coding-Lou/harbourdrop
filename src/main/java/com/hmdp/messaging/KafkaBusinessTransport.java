package com.hmdp.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.IBusinessEventService;
import com.hmdp.service.impl.BusinessEventServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka transport for durable business events relayed from {@code tb_business_outbox}.
 *
 * <p>Each logical consumer group reads its own topic ({@code hmdp.biz.<group>}). The record key is
 * the aggregate ID, so events of one order, blog or shop stay ordered within a partition. Offsets
 * are committed per record only after {@link IBusinessEventService#consume} returns, and the Inbox
 * makes redelivery harmless. A failure is retried with backoff until the business layer either
 * succeeds or quarantines it ({@code recordConsumptionFailure} returns true); if even that cannot
 * be stored, the record ends up on {@code <topic>.DLT} after the retry budget.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.mq.enabled", havingValue = "true")
public class KafkaBusinessTransport implements EventPublisher {
  /** Backstop above {@link DeliveryRetryPolicy#MAX_CONSUME_ATTEMPTS}, which quarantines first. */
  static final int MAX_LISTENER_RETRIES = 15;

  private final KafkaTemplate<String, String> template;
  private final ConsumerFactory<String, String> consumers;
  private final ObjectMapper json;
  private final IBusinessEventService handler;
  private final MeterRegistry metrics;
  private final AtomicInteger connected = new AtomicInteger();
  private final List<ConcurrentMessageListenerContainer<String, String>> containers =
      new ArrayList<>();

  @Value("${hmdp.mq.group-prefix:hmdp}")
  private String groupPrefix;

  @Value("${hmdp.mq.topic-prefix:hmdp.biz.}")
  private String topicPrefix;

  @Value("${hmdp.mq.consumer-enabled:true}")
  private boolean consumerEnabled;

  @Value("${hmdp.mq.consumer-concurrency:1}")
  private int concurrency;

  @Value("${hmdp.mq.send-timeout-ms:10000}")
  private long sendTimeoutMillis;

  public KafkaBusinessTransport(
      KafkaTemplate<String, String> template,
      ConsumerFactory<String, String> consumers,
      ObjectMapper json,
      IBusinessEventService handler,
      MeterRegistry metrics) {
    this.template = template;
    this.consumers = consumers;
    this.json = json;
    this.handler = handler;
    this.metrics = metrics;
    metrics.gauge("hmdp.mq.client.started", connected);
  }

  public String topicFor(String group) {
    return topicPrefix + group;
  }

  @EventListener(ContextRefreshedEvent.class)
  public synchronized void start() {
    if (connected.get() == 1) {
      return;
    }
    if (consumerEnabled) {
      DefaultErrorHandler errors = errorHandler();
      for (String group : KafkaMessagingConfig.consumerGroups()) {
        ContainerProperties properties = new ContainerProperties(topicFor(group));
        properties.setGroupId(groupPrefix + "-" + group);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setDeliveryAttemptHeader(true);
        properties.setMessageListener(
            (MessageListener<String, String>) record -> consumeRecord(record, group));
        ConcurrentMessageListenerContainer<String, String> container =
            new ConcurrentMessageListenerContainer<>(consumers, properties);
        container.setConcurrency(Math.max(1, concurrency));
        container.setCommonErrorHandler(errors);
        container.setBeanName("hmdp-mq-" + group);
        container.start();
        containers.add(container);
      }
    }
    connected.set(1);
  }

  private DefaultErrorHandler errorHandler() {
    ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
    backOff.setMaxInterval(10000);
    backOff.setMaxAttempts(MAX_LISTENER_RETRIES);
    DefaultErrorHandler errors =
        new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
    errors.addNotRetryableExceptions(DeserializationException.class);
    return errors;
  }

  void consumeRecord(ConsumerRecord<String, String> record, String group) {
    BusinessEvent event = null;
    try {
      event = json.readValue(record.value(), BusinessEvent.class);
      handler.consume(event, group);
    } catch (Exception failure) {
      RuntimeException error =
          failure instanceof RuntimeException
              ? (RuntimeException) failure
              : new IllegalArgumentException("消息格式错误", failure);
      metrics.counter("hmdp.mq.consumptions", "group", group, "result", "retry").increment();
      boolean settled;
      try {
        settled =
            handler.recordConsumptionFailure(
                event, group, brokerId(record), record.value(), error, deliveryAttempt(record));
      } catch (RuntimeException storageFailure) {
        log.warn("消息 {} 的失败状态尚未持久化，保持重试", brokerId(record));
        throw error;
      }
      if (!settled) {
        throw error;
      }
    }
  }

  static String brokerId(ConsumerRecord<?, ?> record) {
    return record.topic() + "-" + record.partition() + "@" + record.offset();
  }

  static int deliveryAttempt(ConsumerRecord<?, ?> record) {
    Header header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
    if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
      return 1;
    }
    return ByteBuffer.wrap(header.value()).getInt();
  }

  @Override
  public void publish(BusinessEvent event) {
    if (connected.get() == 0) {
      throw new IllegalStateException("Kafka 业务消息客户端尚未启动");
    }
    String group = BusinessEventServiceImpl.resolveConsumerGroup(event.getKind());
    try {
      ProducerRecord<String, String> record =
          new ProducerRecord<>(
              topicFor(group),
              String.valueOf(event.getAggregateId()),
              json.writeValueAsString(event));
      record
          .headers()
          .add("eventId", String.valueOf(event.getEventId()).getBytes(StandardCharsets.UTF_8))
          .add("kind", event.getKind().getBytes(StandardCharsets.UTF_8));
      // acks=all + idempotent producer: returning normally means the broker stored the record.
      template.send(record).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka 投递被中断", interrupted);
    } catch (Exception failure) {
      throw new IllegalStateException("Kafka 投递结果未确认", failure);
    }
  }

  @PreDestroy
  public synchronized void stop() {
    connected.set(0);
    for (ConcurrentMessageListenerContainer<String, String> container : containers) {
      container.stop();
    }
    containers.clear();
  }
}
