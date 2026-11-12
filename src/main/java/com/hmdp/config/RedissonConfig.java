package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds Redisson from the same connection details Spring Data Redis uses, so properties,
 * environment variables and Testcontainers service connections all reach both clients.
 */
@Configuration
public class RedissonConfig {

  @Bean(destroyMethod = "shutdown")
  public RedissonClient redissonClient(RedisConnectionDetails redis) {
    RedisConnectionDetails.Standalone standalone = redis.getStandalone();
    Config config = new Config();
    SingleServerConfig server =
        config
            .useSingleServer()
            .setAddress("redis://" + standalone.getHost() + ":" + standalone.getPort())
            .setDatabase(standalone.getDatabase());
    if (redis.getUsername() != null && !redis.getUsername().isEmpty()) {
      server.setUsername(redis.getUsername());
    }
    if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
      server.setPassword(redis.getPassword());
    }
    return Redisson.create(config);
  }
}
