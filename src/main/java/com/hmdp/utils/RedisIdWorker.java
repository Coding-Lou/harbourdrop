package com.hmdp.utils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisIdWorker {
  /** Epoch start timestamp */
  private static final long BEGIN_TIMESTAMP = 1640995200L;

  /** Number of sequence bits */
  private static final int COUNT_BITS = 32;

  private StringRedisTemplate stringRedisTemplate;

  public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
    this.stringRedisTemplate = stringRedisTemplate;
  }

  public long nextId(String keyPrefix) {
    // 1. Generate the timestamp
    LocalDateTime now = LocalDateTime.now();
    long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
    long timestamp = nowSecond - BEGIN_TIMESTAMP;

    // 2. Generate the sequence number
    // 2.1. Get the current date, to day precision
    String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
    // 2.2. Increment
    long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

    // 3. Combine and return
    return timestamp << COUNT_BITS | count;
  }
}
