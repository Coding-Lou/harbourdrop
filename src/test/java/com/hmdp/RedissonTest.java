package com.hmdp;

import com.hmdp.support.IntegrationTestBase;
import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
class RedissonTest extends IntegrationTestBase {

  @Resource
  private RedissonClient redissonClient;

  private RLock lock;

  @BeforeEach
  void setUp() {
    lock = redissonClient.getLock("order");
  }

  @Test
  void method1() throws InterruptedException {
    // Try to acquire the lock
    boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
    if (!isLock) {
      log.error("Failed to acquire lock .... 1");
      return;
    }
    try {
      log.info("Acquired lock .... 1");
      method2();
      log.info("Running business logic ... 1");
    } finally {
      log.warn("Releasing lock .... 1");
      lock.unlock();
    }
  }

  void method2() {
    // Try to acquire the lock
    boolean isLock = lock.tryLock();
    if (!isLock) {
      log.error("Failed to acquire lock .... 2");
      return;
    }
    try {
      log.info("Acquired lock .... 2");
      log.info("Running business logic ... 2");
    } finally {
      log.warn("Releasing lock .... 2");
      lock.unlock();
    }
  }
}
