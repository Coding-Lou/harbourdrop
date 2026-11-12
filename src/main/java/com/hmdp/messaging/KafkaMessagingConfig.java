package com.hmdp.messaging;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares one business topic (plus its dead-letter topic) per logical consumer group. Spring's
 * KafkaAdmin creates missing topics at startup, so the broker can keep auto-creation disabled.
 */
@Configuration
@ConditionalOnProperty(name = "hmdp.mq.enabled", havingValue = "true")
public class KafkaMessagingConfig {

  public static final String DEAD_LETTER_SUFFIX = ".DLT";

  @Bean
  public KafkaAdmin.NewTopics businessTopics(
      @Value("${hmdp.mq.topic-prefix:hmdp.biz.}") String prefix,
      @Value("${hmdp.mq.topic-partitions:3}") int partitions,
      @Value("${hmdp.mq.topic-replication-factor:1}") short replicas) {
    List<NewTopic> topics = new ArrayList<>();
    for (String group : consumerGroups()) {
      String topic = prefix + group;
      topics.add(TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build());
      // Dead letters are published to the same partition number, so match the partition count.
      topics.add(
          TopicBuilder.name(topic + DEAD_LETTER_SUFFIX)
              .partitions(partitions)
              .replicas(replicas)
              .build());
    }
    return new KafkaAdmin.NewTopics(topics.toArray(new NewTopic[0]));
  }

  /** Distinct consumer groups in declaration order; each owns exactly one topic. */
  public static Set<String> consumerGroups() {
    Set<String> groups = new LinkedHashSet<>();
    for (BusinessEventType type : BusinessEventType.values()) {
      groups.add(type.getConsumerGroup());
    }
    return groups;
  }
}
