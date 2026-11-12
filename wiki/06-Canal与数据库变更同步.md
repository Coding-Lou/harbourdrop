# 06 Debezium 与数据库变更同步

[返回目录](README.md) · [上一章](05-五层缓存与一致性.md) · [下一章：优惠券订单](07-优惠券与订单生命周期.md)

**本章学习目标**：本章先讲直接改数据库为什么绕过应用失效，再解释 binlog、Debezium 连接器、Kafka topic、投影、检查点与 offset 提交。读完应能说明一条券归属变更会影响哪些缓存，以及失败批次为什么可以重放。

> 迁移说明：本章文件名里的“Canal”是为了不破坏已有链接。迁移前项目用阿里 Canal 读取 binlog，Java 端的 `CanalCacheConsumer` 通过 Canal 客户端拉取批次，并用 Redisson 锁选出唯一的消费实例。`na-stack-migration` 分支（提交 c5ec2e1）已换成 **Debezium MySQL Connector，运行在 Kafka Connect 上**，变更事件写入 Kafka，Java 端由 `DebeziumCacheConsumer` 消费。缓存失效与回填的规则（`CacheChangeProjector`）、检查点表和 binlog 配置都保留了下来。

**本章导航**

- [1. 为什么应用删缓存还不够](#section-01)
- [2. MySQL 为什么要 ROW 与 FULL](#section-02)
- [3. CDC 读到的行为什么不直接写回缓存](#section-03)
- [4. 批次处理与 offset 提交](#section-04)
- [5. 检查点要分清两种用途](#section-05)
- [6. DDL 与批量失效](#section-06)
- [7. 从一条 UPDATE 看懂 binlog 与 Debezium](#section-07)
  - [业务库和缓存库为什么不自动同步](#section-07-01)
  - [一条券迁店事件的演算](#section-07-02)
  - [当前不同表的处理方式](#section-07-03)
  - [阅读投影器时的顺序](#section-07-04)
- [8. 从“先应用、后提交”推导批次可靠性](#section-08)
- [9. 自己做一次数据库直改验证](#section-09)
  - [断线后你应该看到什么](#section-09-01)
- [10. 如何验证数据库直改也会影响页面](#section-10)
- [11. Debezium/Connect 故障时如何理解现象](#section-11)
- [12. 本章面试说法](#section-12)
- [13. 用一笔修改建立 CDC 的完整认识](#section-13)
  - [CDC 看的是已经提交的变化，不是浏览器动作](#section-13-01)
  - [一张券迁店，实际影响不止一个 Key](#section-13-02)
  - [为什么库存变化不能顺手同步到秒杀 Redis](#section-13-03)
  - [批次内第三条失败，前两条是不是白做了](#section-13-04)
  - [一次能分辨传播来源的实验](#section-13-05)
  - [断线恢复何时需要重建基线](#section-13-06)

<a id="section-01"></a>

## 1. 为什么应用删缓存还不够

管理员在数据库工具里直接把店铺名称修改了，Java 没有收到修改请求，就不会新增应用失效任务。浏览器继续读缓存，仍显示旧名称。

MySQL 的 binlog 记录数据库变更。Debezium 的 MySQL 连接器像一个 MySQL 从库那样订阅这份日志，把每一行变更转成一条 JSON 事件写进 Kafka；Java 从 Kafka 读取事件批次，识别受影响的店铺或券，并使缓存失效、按数据库当前值回填。这就是当前 CDC（Change Data Capture，变更数据捕获）链路。

```text
MySQL 提交变更 → binlog（ROW / FULL）
→ Debezium MySQL Connector（运行在 Kafka Connect）
→ Kafka topic hmdp-cdc.hmdp.tb_shop 等
→ Java DebeziumCacheConsumer → CacheChangeProjector
→ 删除 Redis 数据、推进版本、通知本地缓存，并从数据库读当前值回填
```

| 组件 | 作用 | 在本项目里 |
| --- | --- | --- |
| Kafka Connect | 运行连接器的通用框架，负责连接器的配置、位点与状态存储 | Compose 服务 `connect`，镜像 `quay.io/debezium/connect:3.7.1.Final`，REST 接口 127.0.0.1:8083 |
| Debezium MySQL Connector | 读 binlog，产出变更事件 | 名为 `hmdp-cache` 的连接器，配置在 [hmdp-cache-connector.json](../dev/debezium/hmdp-cache-connector.json) |
| Kafka | 保存变更事件，按 offset 供消费者读取 | 与业务消息共用同一个 broker |
| `DebeziumCacheConsumer` | 拉取批次、投影、写检查点、提交 offset | 消费组 `hmdp-cache-cdc` |
| `CacheChangeProjector` | 决定删哪些 Key、要不要回填 | 与 CDC 产品无关，只认 `CdcRowChange` |

核心源码：[DebeziumCacheConsumer](../src/main/java/com/hmdp/cache/DebeziumCacheConsumer.java)、[DebeziumEvents](../src/main/java/com/hmdp/cache/DebeziumEvents.java)、[CdcRowChange](../src/main/java/com/hmdp/cache/CdcRowChange.java)、[CacheChangeProjector](../src/main/java/com/hmdp/cache/CacheChangeProjector.java)、[CacheCheckpointServiceImpl](../src/main/java/com/hmdp/service/impl/CacheCheckpointServiceImpl.java)。

<a id="section-02"></a>

## 2. MySQL 为什么要 ROW 与 FULL

当前 Compose MySQL 启动参数包含 `--server-id=1 --log-bin=mysql-bin --binlog-format=ROW --binlog-row-image=FULL --binlog-expire-logs-seconds=604800`。ROW 记录行变化，FULL 为变更提供完整的前后列信息，便于识别主键和关联店铺变化。

例如一张券从店铺 A 调整到店铺 B，需要同时失效 A、B 的券列表。只看更新后的 shop_id，就可能漏掉旧店铺 A。Debezium 事件里的 `before` 就来自 FULL 行映像。投影器会考虑前后关联，对单条券变更中的旧、新店铺 ID 去重后失效。当前批次循环仍逐条调用投影，尚未把整批所有重复目标统一归并。

连接器以一个专用的最小权限账号连接 MySQL：[02-debezium.sql](../dev/mysql/02-debezium.sql) 创建 `debezium` 用户，授予 `SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT`。MySQL 的 initdb 脚本只在数据卷第一次初始化时执行，所以 Compose 另有一个一次性服务 `cdc-user-init`，每次启动都对已有数据卷幂等地执行同一份 SQL（`CREATE USER IF NOT EXISTS`）。连接器自己作为“从库”也需要一个不与主库冲突的 `database.server.id`（配置为 5401）。

当前订阅范围由连接器配置 `table.include.list` 限定为 `tb_shop`、`tb_voucher`、`tb_seckill_voucher` 三张表。不是订阅全库的所有表，也不是用 CDC 代替所有业务消息。不在名单里的表（包括检查点表本身）根本不会产生事件。

<a id="section-03"></a>

## 3. CDC 读到的行为什么不直接写回缓存

假设消费者正在补处理积压的旧事件，而数据库又发生了更新。如果把旧事件里的 `after` 行直接当当前对象写入缓存，就可能把过时数据复活。

项目以失效为主；店铺和券变更后还会主动读取当前数据库重建相应元数据。当前店铺 CDC 重建基础 TTL 是 20 分钟，而普通详情回源常量是 30 分钟；两者并不统一。涉及重建时以当前数据库状态为依据。**Debezium 负责指出“哪里变了”，数据库提供“现在是什么”。** 这与缓存版本栅栏一起减少过时回填，也是后文“整批重放是安全的”的前提：同一个事件处理两遍，第二遍读到的仍是数据库当前值。

秒杀交易 Redis 状态有预占和在途请求，不能把数据库中的剩余库存简单覆盖到 Redis。CDC 的缓存失效和秒杀恢复是不同操作，库存恢复必须考虑预约事实与在途消息。

<a id="section-04"></a>

## 4. 批次处理与 offset 提交

`DebeziumCacheConsumer` 用 Spring for Apache Kafka 的监听容器订阅四个 topic（三张表的数据 topic 加一个 DDL topic），每次 poll 拿到的记录（最多 200 条）作为一个批次：

1. 逐条解析事件，相关的行交给投影器，DDL 交给整表失效。
2. 有行变更时，把最后一条的位置写入检查点表。
3. 调用 `ack.acknowledge()`，容器才提交这批的 Kafka offset（`AckMode.MANUAL`）。

任何一步抛异常，这批 offset 都不提交。错误处理器 `DefaultErrorHandler` 按指数退避（0.5 秒起，每次翻倍，最长间隔 10 秒）把消费位置拨回批次开头，**整批重放，永不跳过**。不跳过是故意的：跳过一批意味着其中的失效永远不会执行，缓存会一直停在旧值，而且没有任何错误信号。宁可停在这里重试并报警。

“至少一次”意味着某批可能被重复投影。删除缓存与推进版本必须允许重复；投影器回填时重新读数据库当前值，所以重放不会写回旧内容。重复失效主要带来额外加载，不应让业务数据重复扣减。

迁移前用 Redisson 锁保证多个 Java 实例里只有一个在投影。现在这个锁已删除，由 **Kafka 消费组的分区分配**保证：同一消费组内，每个分区同一时刻只分配给一个消费者。连接器为每张表的 topic 只建 1 个分区（`topic.creation.default.partitions=1`），因此同一张表的变更只由一个消费者按 binlog 顺序处理。重平衡与网络异常仍可能让某批被另一个实例再处理一次，所以仍要靠幂等投影兜底，不能宣传只处理一次。

只含无关内容（其他 schema、tombstone）的批次同样提交 offset，并记为 `result=ignored`，不写检查点。

<a id="section-05"></a>

## 5. 检查点要分清两种用途

`tb_cache_cdc_checkpoint` 是给人看的审计记录：`destination` 存消费组名 `hmdp-cache-cdc`，`batch_id` 存最后一条已应用事件的 Kafka offset，`binlog_file` / `binlog_offset` 取自事件的 `source.file` / `source.pos`，`event_time_ms` 是事件时间，`processed_at` 是写入时间。它回答“最后处理到哪里、延迟多少”，**不是**恢复消费的依据。

真正负责“从哪里继续”的位点有两层，都不在这张表里：

| 位点 | 保存在哪里 | 谁用它续读 |
| --- | --- | --- |
| 连接器读到的 binlog 位置 | Kafka Connect 内部 topic `hmdp_connect_offsets` | Debezium 连接器重启后从这里继续读 binlog |
| 消费者读到的 Kafka offset | Kafka 的消费组 `hmdp-cache-cdc` 提交记录 | `DebeziumCacheConsumer` 重启后从这里继续读 topic |

另外两个 Connect 内部 topic `hmdp_connect_configs`、`hmdp_connect_statuses` 保存连接器配置和状态，`hmdp-cdc.schema-history` 保存连接器解析 binlog 所需的表结构历史。这些都在 Kafka 数据卷里；删掉 Kafka 卷相当于让连接器失去全部记忆。

MySQL 保留的 binlog 决定历史能追多久。当前开发配置 binlog 保留 604800 秒，约 7 天。如果连接器停机太久，所需日志已被清理，就不能指望自动续读；需要重新建立基线并重建相关缓存。

<a id="section-06"></a>

## 6. DDL 与批量失效

连接器配置 `include.schema.changes=true`，表结构变更会作为 schema-change 事件写入与前缀同名的 topic `hmdp-cdc`。消费者同时订阅这个 topic；`DebeziumEvents.isSchemaChange` 识别带 `ddl` 或 `tableChanges` 字段的事件，取出涉及的库和表，对监听范围内的表调用 `projectSchemaChange`，把这张表的整个缓存命名空间失效（`tb_shop` 对应 `cache:shop:`，`tb_voucher` 对应 `cache:voucher-meta:` 与 `cache:voucher-list:`），并计数 `hmdp.cdc.ddl`。

不要把这个 topic 与 `hmdp-cdc.schema-history` 混淆：后者是 Debezium 自己用来理解 binlog 行格式的内部记录，Java 不读它。

失效使用 Redis SCAN 分批进行，而不是在正常业务里对全 Redis 执行阻塞式 KEYS。SCAN 不是全库一致性快照，也不是“零成本”。大规模结构变化仍需要考虑并发写入和扫描时长，不能把当前教学规模的实现直接当成任意大数据量的无风险操作。

<a id="section-07"></a>

## 7. 从一条 UPDATE 看懂 binlog 与 Debezium

<a id="section-07-01"></a>

### 业务库和缓存库为什么不自动同步

执行 `UPDATE tb_shop SET name=... WHERE id=...` 时，MySQL 改的是自己的行。Redis 没有直接订阅 MySQL 表，Java 也不一定经过这次写操作。binlog 为我们提供已经发生的变更记录，Debezium 读取它，转成事件写入 Kafka。

数据库的 SQL 文本、binlog 行事件、Debezium 变更事件、项目内的 `CdcRowChange` 和业务缓存 Key 是五个层次。连接器使用 JSON converter 且关闭了 schema（`value.converter.schemas.enable=false`），一条更新事件大致是这样（数值为示意）：

```json
{
  "before": {"id": 1, "name": "旧名称", "...": "..."},
  "after":  {"id": 1, "name": "新名称", "...": "..."},
  "op": "u",
  "source": {"db": "hmdp", "table": "tb_shop", "file": "mysql-bin.000003", "pos": 4567, "ts_ms": 1760000000000},
  "ts_ms": 1760000000123
}
```

[DebeziumEvents](../src/main/java/com/hmdp/cache/DebeziumEvents.java) 把它翻译成产品无关的 [CdcRowChange](../src/main/java/com/hmdp/cache/CdcRowChange.java)：`schema`、`table`、`operation`（`c`→CREATE、`u`→UPDATE、`d`→DELETE、`r`→READ，READ 是快照读，按更新处理）、`before`、`after`（列值统一转成字符串，null 列省略）。投影器只认 `CdcRowChange`，不 import 任何 Debezium 或 Canal 的类，这也是这次从 Canal 换到 Debezium 时投影规则可以原样保留的原因。

连接器 `snapshot.mode=no_data`：首次启动只记录表结构，不把三张表的现有数据当作 `r` 事件全部发一遍，只关心之后的变更。缓存本来就能按需从数据库加载，不需要初始全量。

<a id="section-07-02"></a>

### 一条券迁店事件的演算

假设券 7 从店铺 1 移到店铺 2：

| 数据 | 修改前（before） | 修改后（after） | 要处理的缓存 |
| --- | --- | --- | --- |
| tb_voucher.id | 7 | 7 | cache:voucher-meta:7 |
| tb_voucher.shop_id | 1 | 2 | cache:voucher-list:1 与 :2 |

只删 `cache:voucher-meta:7` 不够，两个店铺的券目录都可能旧。使用 FULL row image 才能在 `before` 里取得旧关联，但代码仍要处理缺少主键等异常内容，不能盲目猜 ID：`parseRowId` 找不到主键时直接抛异常，让这批进入重试并报警，而不是静默跳过。

<a id="section-07-03"></a>

### 当前不同表的处理方式

| 表或事件 | 实际动作 | 为什么 |
| --- | --- | --- |
| tb_shop 行变化 | 失效详情；非删除时从当前库主动重建 | 防止继续用旧展示数据 |
| tb_voucher 行变化 | 失效券元数据与前后店铺目录；非删除时重建元数据 | 券属性与聚合目录关联 |
| tb_seckill_voucher 变化 | 计数，但不把库存投影回普通元数据缓存 | 活动时间/库存本来实时读取；Redis 预占不是展示库存 |
| 三张表的 DDL | 对相关缓存命名空间失效 | 旧序列化结构可能不适配 |
| 其他 schema 的事件、tombstone | 跳过，照常提交 offset | 避免无关内容阻塞进度 |

因此“监听某张表”不代表每一列变化都会直接更新执行库存。交易恢复有自己的流程。

<a id="section-07-04"></a>

### 阅读投影器时的顺序

先看 `isRelevant`：schema 是否匹配 `hmdp.cdc.schema`、表是否在三张表的范围。再看 `projectRowChange`：确定身份行，按表类型分支，失效与必要的当前库重读。最后看 `projectSchemaChange`。

以下为 [CacheChangeProjector.java](../src/main/java/com/hmdp/cache/CacheChangeProjector.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
public boolean isRelevant(String changeSchema, String table) {
  return schema.equals(changeSchema) && WATCHED_TABLES.contains(table);
}

public void projectRowChange(CdcRowChange change) {
  log.debug("CDC row {}.{} op={}", change.schema(), change.table(), change.operation());
  if (!isRelevant(change.schema(), change.table())) {
    return;
  }
  Map<String, String> identity = change.after().isEmpty() ? change.before() : change.after();
  if ("tb_shop".equals(change.table())) {
    projectShopChange(parseRowId(identity, "id"), change.operation());
  } else if ("tb_voucher".equals(change.table())) {
    projectVoucherChange(
        parseRowId(identity, "id"), change.operation(), change.before(), change.after());
  }
  // 活动库存与窗口实时查库；CDC 不覆盖 Redis 的执行库存或购买资格。
  metrics.counter("hmdp.cdc.rows", "table", change.table()).increment();
}

/** A DDL statement may change how rows decode, so drop every cached entry of that table. */
public void projectSchemaChange(String changeSchema, String table) {
  if (!isRelevant(changeSchema, table)) {
    return;
  }
  invalidateTableMetadata(table);
  metrics.counter("hmdp.cdc.ddl", "table", table).increment();
}
```

以下为 [CacheChangeProjector.java](../src/main/java/com/hmdp/cache/CacheChangeProjector.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
private void projectShopChange(long shopId, CdcRowChange.Operation operation) {
  cache.invalidate("cache:shop:" + shopId);
  if (operation != CdcRowChange.Operation.DELETE) {
    cache.queryWithMutex(
        "cache:shop:",
        shopId,
        Shop.class,
        id -> shopServiceProvider.getObject().getById(id),
        CDC_SHOP_TTL_MINUTES,
        TimeUnit.MINUTES);
  }
}

private void projectVoucherChange(
    long voucherId,
    CdcRowChange.Operation operation,
    Map<String, String> before,
    Map<String, String> after) {
  cache.invalidate("cache:voucher-meta:" + voucherId);
  // 券换店后旧、新店铺都需要失效；这里只对当前行的关联店铺去重。
  Set<String> shopIds = new HashSet<>();
  shopIds.add(before.get("shop_id"));
  shopIds.add(after.get("shop_id"));
  shopIds.remove(null);
  for (String shopId : shopIds) {
    cache.invalidate("cache:voucher-list:" + Long.parseLong(shopId));
  }
  if (operation != CdcRowChange.Operation.DELETE) {
    cache.queryWithMutex(
        "cache:voucher-meta:",
        voucherId,
        Voucher.class,
        id -> voucherServiceProvider.getObject().getById(id),
        VOUCHER_METADATA_TTL_MINUTES,
        TimeUnit.MINUTES);
  }
}
```

`identity = after.isEmpty() ? before : after` 用于确定变更主键；删除时 after 为空，要从 before 找身份。对券目录使用 Set 去重前后店铺；这个 Set 仅属于当前行处理，并非整批统一去重器。

回填调用的是第 05 章的 `queryWithMutex`，传入的回源函数查的是数据库当前值，事件里的 `after` 只用来定位 Key。`ObjectProvider` 延迟取得 Service，减少初始化依赖循环；它不表示开启一个新微服务，也不改变方法最终仍查询当前 MySQL 的事实。

<a id="section-08"></a>

## 8. 从“先应用、后提交”推导批次可靠性

Kafka 消费者有两种提交 offset 的时机：先提交再处理（处理失败就丢了），或先处理再提交（失败可以重来，代价是可能重复）。缓存失效丢不起，重复则无害，所以选后者。假设一个批次有 10 条，前 8 条删缓存成功、第 9 条失败；如果已经提交了整批 offset，剩余失败就不会再被读到。当前失败时不提交，错误处理器把位置拨回批次开头，前 8 条会再执行一次，所以投影要允许重复。

以下为 [DebeziumCacheConsumer.java](../src/main/java/com/hmdp/cache/DebeziumCacheConsumer.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
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
```

分段解释：

1. 遍历记录：tombstone（值为 null）跳过；DDL 事件走整表失效；行事件转成 `CdcRowChange` 交给投影器，同时记下最后一条的 binlog 位置和 Kafka offset。
2. 有行变更时保存检查点：消费组名、Kafka offset、binlog 文件名与位置、事件时间。只有 DDL 的批次没有 binlog 位置，不写检查点。
3. 之后才 `ack.acknowledge()`；容器在这之后提交 offset。
4. 记录事件延迟，负时间差按 0 处理，避免时钟差造成负指标。
5. 任何异常都计一次 `result=retry` 并重新抛出，交给 `DefaultErrorHandler` 退避后整批重放。

保存检查点与提交 Kafka offset 不是一个跨组件原子事务。检查点写成功、offset 提交前进程退出，重启后这批还会再来一次；不要把检查点当成“已确认”。缓存失效能重复，但这段代码不适合直接复制去做非幂等扣款。

[DebeziumCacheConsumerTest](../src/test/java/com/hmdp/cache/DebeziumCacheConsumerTest.java) 单测了这套语义：行和 DDL 都投影后先写检查点再确认；投影失败时不确认、计 retry 并抛出；topic 名称符合 Debezium 的命名规则。

<a id="section-09"></a>

## 9. 自己做一次数据库直改验证

先确认链路在运行。Compose 启动后，`connect-init` 会用 `PUT /connectors/hmdp-cache/config` 注册连接器（PUT 是幂等的，重复启动只会更新配置）：

```bash
# connector 与 tasks[0] 的 state 都应为 RUNNING
curl http://127.0.0.1:8083/connectors/hmdp-cache/status
# 应能看到 hmdp-cdc.hmdp.tb_shop 等 topic（某张表第一次变更前，它的 topic 可能还不存在）
docker compose -f dev/docker-compose.yml exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

然后在自己的演示环境选一条店铺，记录 id 和原始名称，不使用别人正在体验的店铺。进入 Compose MySQL（`docker compose -f dev/docker-compose.yml exec mysql mysql -uroot -p hmdp`，本地默认密码见 Compose 文件）；以下是数据库客户端中的教学操作，实际 ID/名称要替换。

```sql
SELECT id,name FROM tb_shop WHERE id = 你的演示店铺ID;
UPDATE tb_shop SET name = '文档CDC测试名称' WHERE id = 你的演示店铺ID;
SELECT id,name FROM tb_shop WHERE id = 你的演示店铺ID;
-- 检查点：processed_at 应在 UPDATE 之后，binlog_file 形如 mysql-bin.xxxxxx
SELECT * FROM tb_cache_cdc_checkpoint WHERE destination = 'hmdp-cache-cdc';
```

确认数据库已改，再看检查点、Java 日志（`docker compose -f dev/docker-compose.yml logs --tail 100 java`）、指标 `hmdp.cdc.batches{result="success"}` 与页面。最后 UPDATE 恢复事先记录的原名，并再次验证传播。不要省略原值记录，也不要把“你的演示店铺ID”原样当合法 SQL。

一个已知现象：某张表的**第一条**变更会触发 Debezium 创建这张表的 topic，消费者要等一次元数据刷新（`metadata.max.age.ms=5000`）和重平衡才能订阅上，所以第一次传播会比较慢；之后是秒级。第一次没立刻生效时先等一会儿，不要急着判定链路坏了。

最有说服力的记录包括：修改前页面、数据库提交证据、CDC 处理时间/检查点、修改后页面。如果只有前后截图，不能排除缓存自然过期造成更新。

<a id="section-09-01"></a>

### 断线后你应该看到什么

这里有两个可以分别中断的环节，表现不同：

| 中断的环节 | 期间数据库直改 | 恢复后 |
| --- | --- | --- |
| Kafka Connect / 连接器停了 | 变更只留在 binlog，Kafka 里没有新事件，页面暂不反映 | 连接器按 `hmdp_connect_offsets` 里的 binlog 位置续读，只要 binlog 还在就能追上 |
| Java 消费者停了 | 事件已在 Kafka 里排队，页面暂不反映 | 消费者按消费组已提交的 offset 续读，积压的事件被处理 |

第二种情况由 `CacheCdcIntegrationTest.restartedConsumerResumesFromCommittedOffset` 验证：停止消费者后插入一行，重启消费者后这行最终进入缓存。如果 Java 写接口的 Outbox 仍正常，它的更新可能照常反映。两条更新来源必须分别验收。

不建议在共享演示环境随意停组件或删 Kafka 数据卷。要演示故障，把影响局限在隔离项目，并保存操作前后状态。

<a id="section-10"></a>

## 10. 如何验证数据库直改也会影响页面

自动化验证见 [CacheCdcIntegrationTest](../src/test/java/com/hmdp/cache/CacheCdcIntegrationTest.java)。它用 Testcontainers 启动真实的 Debezium Connect 容器（与测试用的 MySQL、Kafka 在同一个容器网络），注册连接器并等待 RUNNING 后再开始改库（连接器开始读 binlog 之前的变更不会被捕获），然后验证：

- 直接 SQL 修改店铺（绕过 Service 与 Outbox）后，缓存版本推进、Redis 中回填为新值、检查点表记下了 `mysql-bin.*` 位置；一个全新的 `CacheClient` 读这家店直接命中 Redis，不回源。
- 券从店铺 1 改到店铺 2 后，两家店的券目录版本都推进，券元数据回填为新值。
- 消费者停止期间提交的变更，在重启后被处理。

若手工在本地演示库操作，先选择自己的演示店铺并记录原值：

1. 打开店铺，确认缓存已经产生。
2. 使用数据库工具修改该店铺的测试名称，不走 Java 写接口。
3. 观察连接器状态、Java 日志、CDC 检查点和 `hmdp.cdc.*` 指标。
4. 刷新页面，等待消费和短缓存过期后确认新名称。
5. 恢复原值，再确认恢复也可以传播。

第 4 步出现变化仅能说明数据最终更新了；要证明走的是 CDC，需要同时查看变更消费证据，避免恰好缓存 TTL 到期造成误判。页面普通访客不能直接执行数据库修改与 CDC 断线测试，体验页会把历史隔离证据与当前读路径分别展示。

<a id="section-11"></a>

## 11. Debezium/Connect 故障时如何理解现象

Java 正常、店铺页面能打开，并不表示 CDC 正常。连接器停止后，应用内更新可能仍通过 Outbox 失效，而数据库直改无法及时传播；两条路径的表现不同。

排查顺序：

1. `connect` 容器是否在运行，`curl http://127.0.0.1:8083/connectors/hmdp-cache/status` 中 connector 与 task 是否都是 RUNNING；FAILED 时状态里带有异常堆栈。
2. `connect-init` 是否成功完成（连接器是否注册过）。
3. `debezium` 账号是否存在、权限是否齐全（`cdc-user-init` 是否成功）。
4. MySQL binlog 设置是否仍是 ROW / FULL。
5. Kafka 中是否有 `hmdp-cdc.hmdp.tb_shop` 等 topic；消费组 `hmdp-cache-cdc` 的 lag（`kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group hmdp-cache-cdc`）。
6. Java 指标：`hmdp.cdc.connected`（分区已分配为 1）、`hmdp.cdc.poll.age.seconds`（距上次 poll 的秒数，空闲时也会刷新，持续变大说明消费线程卡住）、`hmdp.cdc.batches{result="retry"}`、`hmdp.cdc.event.delay`。
7. 最近检查点的 `processed_at`；是否已超出 binlog 保留范围。

对应的告警规则是 `CacheCdcDisconnected`、`CacheCdcPollingStalled`、`CacheCdcRetries`、`CacheCdcDelay`（迁移时删除了 Canal 抓取任务和 `CanalServerDown` 告警）。

一个需要理解的失败形态：某批中有一条事件始终处理失败（例如缺主键，或回填时数据库不可用），错误处理器会按退避一直重放这批，`retry` 计数持续增长，lag 不断变大，后续变更也停在后面。这是“永不跳过”的代价，换来的是不会悄悄留下旧缓存。处理方式是看 Java 日志找到原因并修复，而不是去手动推进 offset。

缓存过时首先是查询一致性问题，不能用“把所有业务 Redis 清空”来修复，特别是不能误删登录、签到和秒杀状态。准确识别缓存命名空间，再按受影响范围处理。

<a id="section-12"></a>

## 12. 本章面试说法

“应用事务内写入缓存失效 Outbox 处理正常更新，Debezium 监听数据库直接变更作为补充：MySQL Connector 跑在 Kafka Connect 上，把 binlog 行变更写进 Kafka，每表一个分区保证顺序。Java 端按 poll 批次处理，券变更会对单行的旧、新关联店铺去重失效，回填时重读数据库当前值；全部应用并写完检查点后才手动提交 offset，失败就整批退避重放、不跳过。多实例由消费组分区分配保证同一张表只有一个消费者。缓存回填通过版本和锁 owner 防止旧加载污染。但通知与 CDC 都存在延迟，系统是有边界的最终一致性。”

追问断线超过日志保留时间时，要承认需要重建基线，而不是说“有 checkpoint 就永远能恢复”。追问为什么从 Canal 换成 Debezium 时，能说清楚的是这次迁移的事实：变更改走与业务消息共用的 Kafka，位点由 Kafka Connect 与消费组管理，选主锁被消费组取代，投影器通过 `CdcRowChange` 与具体 CDC 产品解耦；不要编造性能对比数字。

<a id="section-13"></a>

## 13. 用一笔修改建立 CDC 的完整认识

<a id="section-13-01"></a>

### CDC 看的是已经提交的变化，不是浏览器动作

用户通过 Java 修改店铺，与管理员在数据库工具里直接 UPDATE 店铺，最终都可能产生 MySQL binlog。Debezium 读取数据库变更日志，再由 Java 的 CDC 消费者从 Kafka 读出解析。浏览器只是其中一种写入来源，不是 CDC 的上游协议。

因此 CDC 不是“每隔几秒查询店铺表”。它关注已提交事务产生的变更记录，并按日志位置继续读取。MySQL 回滚的那次业务修改不会变成同样的已提交行变化。阅读这条链路时，先确认数据库提交，再讨论 Kafka 里出现了哪条事件、哪个批次处理了它。

ROW 表示日志记录行变化；FULL 提供完整行映像，事件里的 `before` 因此带着全部旧列，有利于拿到关联字段。例如券从店铺 101 移到 202，需要知道旧 shop_id 和新 shop_id，分别让两个店铺的券列表失效。只清新店铺，会让旧店铺继续展示已经迁走的券。

<a id="section-13-02"></a>

### 一张券迁店，实际影响不止一个 Key

假设教学券 ID=700，before.shop_id=101、after.shop_id=202。投影器先失效 `cache:voucher-meta:700`，再对该行的旧、新店铺去重，失效 `cache:voucher-list:101` 与 `cache:voucher-list:202`。若两边仍是同一家店，去重后只处理一次。

处理 INSERT（`op=c`）时没有旧行、处理 DELETE（`op=d`）时没有新行，因此身份提取必须按事件类型和实际列内容理解。删除后不能再按“回源当前券并写缓存”的正常新增流程处理；库中已经不存在这条记录。

当前店铺 CDC 回源基础 TTL 是 20 分钟，普通详情路径基础 TTL 是 30 分钟；券元数据基础 TTL 是 10 分钟，实际写入可能有抖动。它们是当前实现差异，不要在演示时把所有详情 TTL 都说成固定 30 分钟。

<a id="section-13-03"></a>

### 为什么库存变化不能顺手同步到秒杀 Redis

券元数据是查询副本，秒杀执行库存却已经承担准入职责。假设 MySQL 库存仍为 5，Redis 已受理 2 人但后台履约还未完成，Redis 剩余是 3。此时用数据库的 5 覆盖 Redis，会把已受理的两份库存再次开放。

因此投影器不会把 `tb_seckill_voucher` 的数据库库存直接复制到 Redis 执行库存。执行库存恢复需要检查未完成工作、购买资格和活动基线，属于秒杀发布/恢复流程。表名里有“库存”不意味着任何 CDC UPDATE 都能驱动运行中库存重置。

这是阅读“监听数据库变更”的重要边界：CDC 可以传递变化，但不能替代业务语义。复制完整行并不自动保证正确，尤其是对已经接收请求的执行状态。

<a id="section-13-04"></a>

### 批次内第三条失败，前两条是不是白做了

假设批次 B 有三条相关变化，前两条失效成功，第三条由于依赖故障抛异常。当前消费者不会确认 B，offset 不提交，错误处理器退避后把位置拨回 B 的开头。之后三条都会重新处理。

前两条不是数据库回滚到未失效状态，而是下次重复失效。失效通常能重复，重复增加缓存版本只会继续淘汰旧副本；回填读的是数据库当前值，重复也不会写回旧内容。这是该处理方式能够承受整批重放的基础。若把相同循环改成直接扣款或无条件库存加一，整批重放就可能产生重复副作用。

处理相关批次后会先写检查点，再确认并提交 offset。检查点写成功但 offset 没提交上（例如进程恰好退出），重启后仍会再次收到这批；不要把检查点记录当成跨数据库与 Kafka 的原子确认。它提供观察依据，消费组提交的 offset 负责续读。

<a id="section-13-05"></a>

### 一次能分辨传播来源的实验

先读演示店铺，让查询副本存在，再记录名称与时间。通过数据库工具修改这一条演示记录，不经过 Java 写接口；确认提交成功。随后观察 CDC 批次日志、检查点时间和页面新值，最后恢复原值。

如果通过网页修改，应用提交后的失效与 Outbox 也可能起作用，只凭页面变化无法证明 CDC 参与。因此本实验限定写入来源。反过来，也不要因为数据库直改暂时没传播就认为 Java 写接口一定坏了，两条链路应分别验收。

| 现象 | 应继续检查 | 暂时不能下的结论 |
| --- | --- | --- |
| 数据库仍是旧值 | UPDATE 是否提交、连接了哪个库 | CDC 丢消息 |
| 库已变，Kafka 里没有对应 topic 或事件 | 连接器状态、账号权限、`table.include.list` | 页面实现有问题 |
| Kafka 有事件，检查点没有推进 | 消费者是否分配到分区、是否在重试（看 retry 计数与日志）、首次建 topic 的订阅延迟 | 连接器故障 |
| 检查点推进，但页面暂时旧值 | 上层短副本、采样时刻、店铺 ID | 数据库更新失败 |
| 页面更新但无 CDC 证据 | 是否走了应用失效、是否自然过期 | CDC 链路已验证 |

<a id="section-13-06"></a>

### 断线恢复何时需要重建基线

短暂断线后，若位点和对应的数据仍存在，就可以继续追赶：连接器依赖 binlog 还在，消费者依赖 Kafka topic 里的事件还在。断线超过保留范围，就不能凭一个旧位点读取已删除的数据。当前开发 Compose 的 MySQL binlog 保留设置为 604800 秒，也就是 7 天；这不是无限历史。Kafka topic 自身也有保留期限，同样是边界。

需要重建基线时，应明确目标缓存范围和数据库当前事实，同时确保后续变化不被漏掉。本项目的连接器使用 `snapshot.mode=no_data`，没有实现完整的在线快照加增量切换方案，不能把“保存检查点”描述成已经具备生产级全量重建与灾备能力。初学者至少要能说明断线追赶与日志缺口恢复是两种问题。
