package com.hmdp.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.cache.CacheReadTrace;
import com.hmdp.cache.CacheSnapshot;
import com.hmdp.cache.RequestCacheScope;
import com.hmdp.reliability.RedisScripts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CacheClient {
  private static final long REBUILD_WAIT_SECONDS = 2;
  private static final long REBUILD_LOCK_SECONDS = 10;
  private static final int RETRY_BASE_MILLIS = 20;
  private static final int RETRY_JITTER_MILLIS = 20;
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final AtomicLong generation = new AtomicLong();
  private final LoadingCache<LoadRequest, CacheSnapshot> local =
      Caffeine.newBuilder()
          .maximumSize(10000)
          .expireAfterWrite(5, TimeUnit.SECONDS)
          .recordStats()
          .build(this::loadLayers);
  private final ThreadPoolExecutor rebuild =
      new ThreadPoolExecutor(
          2,
          4,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(100),
          r -> {
            Thread t = new Thread(r, "cache-rebuild");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private static final DefaultRedisScript<Long> UNLOCK = RedisScripts.number("unlock.lua");
  private static final DefaultRedisScript<Long> FILL = RedisScripts.number("cache-fill.lua");
  private static final DefaultRedisScript<Long> INVALIDATE =
      RedisScripts.number("cache-invalidate.lua");

  @Value("${hmdp.cache.enabled:true}")
  private boolean enabled = true;

  private MeterRegistry metrics;

  public CacheClient(StringRedisTemplate redis) {
    this(redis, defaultObjectMapper());
  }

  @Autowired
  public CacheClient(StringRedisTemplate redis, ObjectMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  /** Same settings Spring Boot applies to its ObjectMapper; used when constructed by hand. */
  static ObjectMapper defaultObjectMapper() {
    return JsonMapper.builder()
        .findAndAddModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .build();
  }

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cache value cannot be serialized", e);
    }
  }

  private JsonNode parse(String payload) {
    try {
      JsonNode node = mapper.readTree(payload);
      if (node == null || !node.isObject()) {
        throw new UndecodableCacheValue(null);
      }
      return node;
    } catch (JsonProcessingException e) {
      throw new UndecodableCacheValue(e);
    }
  }

  private static LocalDateTime expireTimeOf(JsonNode node) {
    try {
      return LocalDateTime.parse(node.get("expireTime").asText());
    } catch (RuntimeException e) {
      throw new UndecodableCacheValue(e);
    }
  }

  /** A cached value that cannot be read, such as an entry written by an older serializer. */
  static final class UndecodableCacheValue extends RuntimeException {
    UndecodableCacheValue(Throwable cause) {
      super("Cache content cannot be parsed", cause);
    }
  }

  @Autowired
  public void bindMetrics(MeterRegistry registry) {
    metrics = registry;
    CaffeineCacheMetrics.monitor(registry, local, "business_details");
  }

  @PreDestroy
  public void close() {
    rebuild.shutdownNow();
  }

  /**
   * Advances the generation of the whole local cache namespace; later requests query the new
   * generation.
   *
   * <p>A late load can only write into the old generation; this is logical invalidation, not
   * immediate deletion of every object by key.
   */
  public void advanceLocalCacheGeneration() {
    generation.incrementAndGet();
  }

  public CacheStats stats() {
    return local.stats();
  }

  public void invalidate(String key) {
    advanceLocalCacheGeneration();
    redis.execute(INVALIDATE, Arrays.asList(key, key + ":version"));
  }

  public void invalidatePrefix(String prefix) {
    advanceLocalCacheGeneration();
    redis.execute(
        (RedisCallback<Object>)
            connection -> {
              try (Cursor<byte[]> cursor =
                  connection.scan(
                      ScanOptions.scanOptions().match(prefix + "*").count(200).build())) {
                while (cursor.hasNext()) {
                  String key = new String(cursor.next(), StandardCharsets.UTF_8);
                  if (key.endsWith(":version")) {
                    key = key.substring(0, key.length() - 8);
                  }
                  // Use another pooled connection; mutation during SCAN may
                  // repeat a key, which is harmless.
                  invalidate(key);
                }
              } catch (RuntimeException failure) {
                throw new IllegalStateException("Cache namespace invalidation failed", failure);
              }
              return null;
            });
  }

  private String queryCacheVersion(String key) {
    String value = redis.opsForValue().get(key + ":version");
    return value == null ? "0" : value;
  }

  private void recordCacheReadOutcome(String layer, String result) {
    CacheReadTrace.record(layer, result);
    if (metrics != null) {
      metrics.counter("hmdp.cache.requests", "layer", layer, "result", result).increment();
    }
  }

  /**
   * Converts the duration to milliseconds and adds random jitter of under 10% of the base TTL.
   *
   * <p>Spreads out the expiry of caches written together to reduce simultaneous reloads; the
   * return value is always in milliseconds.
   */
  private long calculateTtlMillisWithJitter(Long duration, TimeUnit durationUnit) {
    long baseTtlMillis = durationUnit.toMillis(duration);
    return baseTtlMillis + ThreadLocalRandom.current().nextLong(Math.max(1, baseTtlMillis / 10));
  }

  public void set(String key, Object value, Long duration, TimeUnit durationUnit) {
    invalidate(key);
    redis
        .opsForValue()
        .set(
            key,
            toJson(value),
            calculateTtlMillisWithJitter(duration, durationUnit),
            TimeUnit.MILLISECONDS);
  }

  public void setWithLogicalExpire(String key, Object value, Long duration, TimeUnit durationUnit) {
    RedisData data = new RedisData();
    data.setData(value);
    data.setExpireTime(LocalDateTime.now().plusSeconds(durationUnit.toSeconds(duration)));
    invalidate(key);
    redis.opsForValue().set(key, toJson(data));
  }

  private <R> R decode(String payload, Class<R> type) {
    JsonNode node = parse(payload);
    if (node.has("expireTime") && node.has("data")) {
      node = node.get("data");
    }
    try {
      return mapper.treeToValue(node, type);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new UndecodableCacheValue(e);
    }
  }

  private boolean isUsableCachePayload(String payload) {
    if (payload.isEmpty()) {
      return true;
    }
    try {
      JsonNode node = parse(payload);
      return !node.has("expireTime") || expireTimeOf(node).isAfter(LocalDateTime.now());
    } catch (RuntimeException corrupt) {
      return false;
    }
  }

  private CacheSnapshot readRedisSnapshot(String key) {
    String json = redis.opsForValue().get(key);
    if (json == null) {
      recordCacheReadOutcome("redis", "miss");
      return null;
    }
    if (!isUsableCachePayload(json)) {
      invalidate(key);
      return null;
    }
    Long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
    if (ttlMillis != null && ttlMillis == -2) {
      return null;
    }
    recordCacheReadOutcome("redis", "hit");
    return new CacheSnapshot(
        json,
        System.currentTimeMillis() + (ttlMillis == null || ttlMillis < 0 ? 30000 : ttlMillis),
        true);
  }

  private CacheSnapshot loadLayers(LoadRequest request) {
    CacheSnapshot cached = readRedisSnapshot(request.key);
    if (cached != null) {
      return cached;
    }
    return awaitOrRebuild(request);
  }

  private CacheSnapshot awaitOrRebuild(LoadRequest request) {
    String key = request.key;
    String lockKey = "lock:cache:" + key;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REBUILD_WAIT_SECONDS);
    do {
      String owner = UUID.randomUUID().toString();
      if (Boolean.TRUE.equals(
          redis
              .opsForValue()
              .setIfAbsent(lockKey, owner, REBUILD_LOCK_SECONDS, TimeUnit.SECONDS))) {
        return rebuildUnderLock(request, lockKey, owner);
      }
      CacheSnapshot cached = readRedisSnapshot(key);
      if (cached != null) {
        return cached;
      }
      pauseBeforeRetry();
    } while (System.nanoTime() < deadline);
    throw new IllegalStateException("Hot key is being rebuilt; please try again later");
  }

  private CacheSnapshot rebuildUnderLock(LoadRequest request, String lockKey, String owner) {
    String key = request.key;
    try {
      CacheSnapshot cached = readRedisSnapshot(key);
      if (cached != null) {
        return cached;
      }
      String revision = queryCacheVersion(key);
      recordCacheReadOutcome("mysql", "load");
      String json = request.source.get();
      long ttlMillis =
          json.isEmpty()
              ? calculateTtlMillisWithJitter(RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES)
              : request.ttlMillis;
      // Check both the pre-load version and the lock owner; a stale load may be returned to this
      // request but must not pollute later cache reads.
      Long written =
          redis.execute(
              FILL,
              Arrays.asList(key, lockKey, key + ":version"),
              json,
              String.valueOf(ttlMillis),
              revision,
              owner);
      CacheSnapshot loaded =
          new CacheSnapshot(
              json, System.currentTimeMillis() + ttlMillis, Long.valueOf(1).equals(written));
      if (!loaded.cacheable) {
        recordCacheReadOutcome("fill", "fenced");
      }
      return loaded;
    } finally {
      redis.execute(UNLOCK, Collections.singletonList(lockKey), owner);
    }
  }

  private void pauseBeforeRetry() {
    try {
      Thread.sleep(RETRY_BASE_MILLIS + ThreadLocalRandom.current().nextInt(RETRY_JITTER_MILLIS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Cache query was interrupted", interrupted);
    }
  }

  /**
   * Kept as the original teaching entry point; now delegates to the unified multi-level cache
   * with mutex-guarded reloads.
   *
   * <p>The name comes from an early cache-penetration example; see queryWithMutex for the actual
   * read strategy.
   */
  public <R, ID> R queryWithPassThrough(
      String prefix,
      ID id,
      Class<R> type,
      Function<ID, R> source,
      Long duration,
      TimeUnit durationUnit) {
    return queryWithMutex(prefix, id, type, source, duration, durationUnit);
  }

  public <R, ID> R queryWithMutex(
      String prefix,
      ID id,
      Class<R> type,
      Function<ID, R> source,
      Long duration,
      TimeUnit durationUnit) {
    if (!enabled) {
      recordCacheReadOutcome("mysql", "load");
      return source.apply(id);
    }
    String key = prefix + id;
    long cacheGeneration = generation.get();
    String scopeKey = key + "@" + cacheGeneration;
    CacheSnapshot snapshot = RequestCacheScope.get(scopeKey);
    if (snapshot != null && snapshot.fresh()) {
      recordCacheReadOutcome("scope", "hit");
    } else {
      recordCacheReadOutcome("scope", "miss");
      LoadRequest request =
          new LoadRequest(
              key,
              cacheGeneration,
              calculateTtlMillisWithJitter(duration, durationUnit),
              () -> {
                R value = source.apply(id);
                return value == null ? "" : toJson(value);
              });
      snapshot = local.getIfPresent(request);
      if (snapshot != null && snapshot.fresh()) {
        recordCacheReadOutcome("jvm", "hit");
      } else {
        if (snapshot != null) {
          local.invalidate(request);
        }
        recordCacheReadOutcome("jvm", "miss");
        snapshot = local.get(request);
      }
      if (!snapshot.cacheable) {
        local.invalidate(request);
      } else if (cacheGeneration == generation.get()) {
        RequestCacheScope.put(scopeKey, snapshot);
      }
    }
    if (snapshot.json.isEmpty()) {
      return null;
    }
    try {
      return decode(snapshot.json, type);
    } catch (UndecodableCacheValue unreadable) {
      // Old or corrupt payload: drop every cached copy and answer from the source this time.
      recordCacheReadOutcome("decode", "invalid");
      invalidate(key);
      return source.apply(id);
    }
  }

  private static final class LoadRequest {
    final String key;
    final long cacheGeneration;
    final long ttlMillis;

    final Supplier<String> source;

    LoadRequest(String key, long cacheGeneration, long ttlMillis, Supplier<String> source) {
      this.key = key;
      this.cacheGeneration = cacheGeneration;
      this.ttlMillis = ttlMillis;
      this.source = source;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof LoadRequest
          && key.equals(((LoadRequest) other).key)
          && cacheGeneration == ((LoadRequest) other).cacheGeneration;
    }

    @Override
    public int hashCode() {
      return 31 * key.hashCode() + Long.hashCode(cacheGeneration);
    }
  }

  public <R, ID> R queryWithLogicalExpire(
      String prefix,
      ID id,
      Class<R> type,
      Function<ID, R> source,
      Long duration,
      TimeUnit durationUnit) {
    String key = prefix + id;
    String json = redis.opsForValue().get(key);

    if (json == null || json.isEmpty()) {
      return queryWithMutex(prefix, id, type, source, duration, durationUnit);
    }
    R value;
    LocalDateTime expireTime;
    try {
      JsonNode node = parse(json);
      value = decode(json, type);
      if (!node.has("expireTime")) {
        return value;
      }
      expireTime = expireTimeOf(node);
    } catch (UndecodableCacheValue corrupt) {
      invalidate(key);
      return queryWithMutex(prefix, id, type, source, duration, durationUnit);
    }
    if (expireTime.isAfter(LocalDateTime.now())) {
      return value;
    }
    String lockKey = "lock:cache:" + key;
    String owner = UUID.randomUUID().toString();

    if (Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(lockKey, owner, 10, TimeUnit.SECONDS))) {
      String revision = queryCacheVersion(key);
      try {
        rebuild.execute(
            () -> {
              try {
                R fresh = source.apply(id);
                RedisData data = new RedisData();
                data.setData(fresh);
                data.setExpireTime(
                    LocalDateTime.now().plusSeconds(durationUnit.toSeconds(duration)));
                redis.execute(
                    FILL,
                    Arrays.asList(key, lockKey, key + ":version"),
                    fresh == null ? "" : toJson(data),
                    String.valueOf(calculateTtlMillisWithJitter(duration * 2, durationUnit)),
                    revision,
                    owner);
                advanceLocalCacheGeneration();
              } catch (RuntimeException e) {
                log.warn("Cache rebuild failed: {}", key, e);
              } finally {
                redis.execute(UNLOCK, Collections.singletonList(lockKey), owner);
              }
            });
      } catch (RejectedExecutionException full) {
        redis.execute(UNLOCK, Collections.singletonList(lockKey), owner);
      }
    }
    return value;
  }
}
