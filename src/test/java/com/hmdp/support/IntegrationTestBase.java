package com.hmdp.support;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Shared infrastructure for Spring integration tests.
 *
 * <p>Containers are started once per JVM (singleton pattern) and reused by every Spring context, so
 * classes that use {@code @DirtiesContext} do not restart MySQL, Redis or Kafka. Spring Boot wires
 * the datasource, Redis and Kafka connections through {@link ServiceConnection}.
 */
@AutoConfigureObservability(tracing = false)
public abstract class IntegrationTestBase {

  /** Integration tests assert this name before deleting rows, so they never touch real data. */
  public static final String DATABASE = "hmdp_optimization_test";

  public static final String BEHAVIOR_TOPIC = "HmdpShopBehavior";

  @ServiceConnection
  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
          .withDatabaseName(DATABASE)
          .withUsername("root")
          .withPassword("isolation_test_only")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--server-id=1",
              "--log-bin=mysql-bin",
              "--binlog-format=ROW",
              "--binlog-row-image=FULL")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Paths.get("src/main/resources/db/hmdp.sql")),
              "/docker-entrypoint-initdb.d/01-base.sql");

  @ServiceConnection(name = "redis")
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  @ServiceConnection
  protected static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

  static {
    Startables.deepStart(MYSQL, REDIS, KAFKA).join();
    createTopics(List.of(new NewTopic(BEHAVIOR_TOPIC, 3, (short) 1)));
  }

  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add("hmdp.analytics.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("hmdp.cdc.schema", () -> DATABASE);
    registry.add("citylens.catalog-admin-ids", () -> "1010");
    registry.add("citylens.payment.mock-enabled", () -> "true");
    registry.add("citylens.reliability.scheduling-enabled", () -> "false");
    registry.add("hmdp.monitor-token", () -> "hmdp_dev_metrics");
  }

  protected static void createTopics(List<NewTopic> topics) {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      for (NewTopic topic : topics) {
        try {
          admin.createTopics(List.of(topic)).all().get();
        } catch (ExecutionException e) {
          if (!(e.getCause() instanceof TopicExistsException)) {
            throw new IllegalStateException("Kafka topic creation failed: " + topic.name(), e);
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
