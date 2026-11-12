# 09 Kafka 可靠业务消息

[返回目录](README.md) · [上一章](08-秒杀Lua与RedisStream.md) · [下一章：Kafka 热榜](10-Kafka行为分析与热榜.md)

**本章学习目标**：先证明直接“保存后发消息”的两个故障窗口，再读数据库任务、租约认领与 Inbox 消费。最后看分批 Feed 和延迟关单。读完应能解释为什么允许重发，却不能重复造成指定业务效果。

> **迁移说明**：本章文件名仍带 RocketMQ，是为了不破坏已有链接。业务消息之前用 RocketMQ 实现（一个业务 Topic 加一个定时 Topic，用 Tag 区分事件类别，用定时消息做延迟关单）；现在换成 Kafka 3.9.1 + Spring for Apache Kafka，与第 10 章的行为分析共用同一个 Kafka broker。Outbox / Inbox、租约（`FOR UPDATE SKIP LOCKED` + owner fencing）、PENDING→SENDING→SENT→DONE、REVIEW、退避与隔离逻辑**全部没有变**，变的只是传输层：Topic 怎么划分、怎么确认写入、怎么提交消费进度、失败怎么重试，以及延迟关单由谁计时。

**本章导航**

- [1. 为什么数据库成功后发消息仍可能丢](#section-01)
- [2. 两条通道怎样分工](#section-02)
- [3. Outbox 的关键字段](#section-03)
- [4. Relay 如何允许多个 worker 同时运行](#section-04)
- [5. Outbox 状态如何理解](#section-05)
- [6. 消费端 Inbox 怎么防重复](#section-06)
- [7. 乱序如何处理](#section-07)
- [8. 延迟关单不是 sleep](#section-08)
- [9. 不开启 MQ 时是什么行为](#section-09)
- [10. 从“业务保存后发消息”推导 Outbox](#section-10)
  - [两种直接发送顺序都存在窗口](#section-10-01)
  - [真实事件登记入口](#section-10-02)
  - [业务 ID、事件 ID 与 Broker ID](#section-10-03)
- [11. 多 worker 怎样领任务又不长时间锁数据库](#section-11)
  - [查询条件与 SKIP LOCKED](#section-11-01)
  - [发送代码怎么分段读](#section-11-02)
  - [发送确认丢失怎样重试](#section-11-03)
- [12. Inbox 与业务状态怎样在同一事务完成](#section-12)
  - [三个消费故障时序](#section-12-01)
  - [怎样决定重试还是隔离](#section-12-02)
- [13. 定时关单为什么还需要数据库扫描](#section-13)
  - [并发付款与关单演算](#section-13-01)
  - [本章验收模板](#section-13-02)
- [14. 页面与运维怎么观察](#section-14)
- [15. 一句话串起这个亮点](#section-15)
- [16. 可靠消息教程：从一条任务推导发送与消费](#section-16)
  - [先选一条事件，给三个 ID 各留一栏](#section-16-01)
  - [逐步推导任务状态，而不是背一串枚举](#section-16-02)
  - [多 worker 并行时，租约保护什么](#section-16-03)
  - [用三次退出演算 Inbox 的价值](#section-16-04)
  - [失败重试为什么要有退避与隔离](#section-16-05)
  - [MQ 开关关闭时，什么仍然成立](#section-16-06)
  - [实操验收分成“投递”和“完成”两栏](#section-16-07)

<a id="section-01"></a>

## 1. 为什么数据库成功后发消息仍可能丢

假设发布笔记先写数据库，随后发消息让后台推送粉丝。数据库刚提交，Java 就退出，消息还没发，笔记存在但粉丝一直看不到动态。

换成先发消息也有问题：消息已经消费，数据库事务后来失败，用户却看到了不存在的笔记。可靠事件需要把“业务事实”和“待发送任务”一起持久化。

当前用本地事务 Outbox：在同一 MySQL 事务写业务表与 `tb_business_outbox`。后台 Relay 扫描任务并发送到 Kafka，失败继续使用原事件 ID 重试。它不是 Kafka 事务（transactional producer），也不是数据库与 Broker 的分布式事务。

<a id="section-02"></a>

## 2. 两条通道怎样分工

现在业务事件和行为分析跑在**同一个 Kafka 集群**上，但用不同的 topic 和不同的可靠性策略：

| 通道 | Topic | 客户端 | 可靠性策略 |
| --- | --- | --- | --- |
| 业务事件（本章） | `hmdp.biz.<group>`，每个逻辑消费组一个，另有 `.DLT` | Spring for Apache Kafka（`KafkaTemplate` + 监听容器） | 数据库 Outbox 持久登记，`acks=all` + 幂等生产者同步确认，消费后逐条提交 offset，Inbox 去重，失败隔离 |
| 行为分析（第 10 章） | `HmdpShopBehavior` | 原生 kafka-clients | 浏览尽力发送、订单走分析 Outbox，消费端按 eventId 去重，整批成功后 `commitSync` |

业务事件负责需要业务恢复与确认的任务：Feed、关注/点赞投影、媒体删除、缓存失效、活动发布、秒杀履约、订单事件与到期关单。迁移前这部分由 RocketMQ 承担，行为分析才用 Kafka；现在两者只是共用 broker，彼此的 topic、消费组和提交方式互不影响。

当前代码：[BusinessOutboxServiceImpl](../src/main/java/com/hmdp/service/impl/BusinessOutboxServiceImpl.java)、[OutboxRelayServiceImpl](../src/main/java/com/hmdp/service/impl/OutboxRelayServiceImpl.java)、[KafkaBusinessTransport](../src/main/java/com/hmdp/messaging/KafkaBusinessTransport.java)（实现 [EventPublisher](../src/main/java/com/hmdp/messaging/EventPublisher.java)）、[KafkaMessagingConfig](../src/main/java/com/hmdp/messaging/KafkaMessagingConfig.java)、[BusinessEventServiceImpl](../src/main/java/com/hmdp/service/impl/BusinessEventServiceImpl.java)、[BusinessEventType](../src/main/java/com/hmdp/messaging/BusinessEventType.java)、[BusinessOutboxRepository](../src/main/java/com/hmdp/repository/BusinessOutboxRepository.java)。

相关配置（[application.yaml](../src/main/resources/application.yaml)）：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `spring.kafka.bootstrap-servers` | `${HMDP_KAFKA_SERVERS:127.0.0.1:9092}` | broker 地址，业务与分析共用 |
| `spring.kafka.producer.acks` / `enable.idempotence` | `all` / `true` | 写入确认与幂等生产者 |
| `spring.kafka.consumer.enable-auto-commit` | `false` | offset 由监听容器按记录提交 |
| `hmdp.mq.enabled` | `${HMDP_MQ_ENABLED:false}` | 关闭时 Relay 走本地消费（见第 9 节） |
| `hmdp.mq.topic-prefix` | `hmdp.biz.` | 业务 topic 前缀 |
| `hmdp.mq.topic-partitions` / `topic-replication-factor` | `3` / `1` | 启动时创建 topic 用的分区数与副本数 |
| `hmdp.mq.group-prefix` | `hmdp` | 消费组名为 `<group-prefix>-<group>`，例如 `hmdp-orders` |
| `hmdp.mq.consumer-concurrency` | `1` | 每个消费组的监听线程数 |
| `hmdp.mq.send-timeout-ms` | `10000` | 同步等待 broker 确认的上限 |

迁移时删除了 RocketMQ 的 nameserver、业务 topic 与定时 topic 配置；本地 compose 也不再启动 nameserver、broker 和初始化容器。

<a id="section-03"></a>

## 3. Outbox 的关键字段

| 字段 | 用途 |
| --- | --- |
| id | 稳定业务事件号，重试不换号 |
| kind / aggregate_id / user_id | 事件类型、业务对象与相关用户 |
| state / attempts / next_attempt_at | 状态、尝试次数、下次可执行时间 |
| lease_owner / lease_until | 哪次 worker 批次暂时持有处理权 |
| sent_at / consumed_at | 发送确认与消费完成时间 |
| deliver_at | 定时任务目标时间 |
| cursor_id | Feed 等分批工作进度 |
| idempotency_key | 秒杀持久交接唯一业务键 |
| last_error | 最近错误，便于复核 |

业务 eventId 不等于 Kafka 的消息位置。Kafka 没有全局 messageId，一条记录由 `topic-partition@offset` 定位，代码里把它拼成 brokerId（例如 `hmdp.biz.orders-0@42`）记入失败表。一次业务重试可能对应多条 Kafka 记录，但业务身份保持不变。事件目前包含对同一数据库任务的引用，若以后拆成独立微服务，必须设计足够完整、兼容演进的消息契约。

<a id="section-04"></a>

## 4. Relay 如何允许多个 worker 同时运行

Relay 在短数据库事务中通过 `FOR UPDATE SKIP LOCKED` 获取一批可执行任务，默认约 20 条；给它们设置批次 UUID owner 和约 60 秒租约，然后提交。网络发消息在事务外执行，避免长时间占数据库行锁。

每次实际处理会核对/续期租约，状态写回带 owner 条件。即使旧 worker 暂停后恢复，它也不应覆盖后来接管者的状态。这叫租约 fencing；只判断“任务现在是 SENDING”不足以识别持有者。

MySQL 8 支持这里使用的 `SKIP LOCKED`。项目的完整开发环境用 MySQL 8；不要仅根据旧课程连接器版本以为任意 MySQL 5.7 都能原样运行当前扩展 SQL。

<a id="section-05"></a>

## 5. Outbox 状态如何理解

典型过程：PENDING → SENDING → SENT → DONE。SENT 只表示 Kafka 已按 `acks=all` 确认写入，不保证消费者业务完成；DONE 才表示对应处理步骤完成。

消费者可能很快，先将任务标成 DONE，发送者才回写 SENT。因此 `markSent` 必须带条件，不能把已完成状态覆盖回未完成。

发送错误会记录原因，按退避规则回到 PENDING，当前自动发送失败约 10 次后进入 REVIEW。已经 SENT 但较长时间没有消费确认的任务也会被定期（每 60 秒）检查，使用原 ID 重新驱动。默认检查阈值约 300 秒；SQL 同时排除 `deliver_at` 还没到的任务，未来定时任务在目标时间前不应被误判。

重复发送是允许的；下游必须防重复。自动重试有限，REVIEW 必须让管理员能看见。

<a id="section-06"></a>

## 6. 消费端 Inbox 怎么防重复

```text
监听容器收到一条记录 → 解析 JSON，核对事件与目标组
→ 数据库事务锁定持久任务，查询 (consumer_group,event_id) Inbox
→ 已处理则走重复路径，否则完成本次投影 / 登记后续分析任务
→ 插入 Inbox、更新任务状态 → 提交数据库事务
→ consume() 正常返回 → 容器提交这一条记录的 offset（AckMode.RECORD）
```

当前先锁定持久事件行再检查 Inbox，处理效果与新增 Inbox 在同一事务提交。重复消息已经有该组 Inbox 时，不再重复完成数据库副作用；唯一主键作为最后约束。数据库提交后、offset 提交前进程退出或发生重平衡，同一条记录会被再次投递，这也是正常场景。

逻辑组按照职责分为 feed、relations、media、cache、activities、fulfillment、orders、deadlines（映射见 `BusinessEventType`，例如 ORDER_CREATED/ORDER_PAID/ORDER_CLOSED → orders，ORDER_EXPIRE → deadlines，SECKILL_FULFILL → fulfillment）。分组并不是让每个消费者都执行所有事件。

**Topic 按消费组划分，取代 RocketMQ 的 Tag 过滤。** 迁移前所有业务事件进同一个 Topic，用 kind 作 Tag，每个消费组按 Tag 订阅自己关心的类别。Kafka 没有 broker 端的 Tag 过滤：消费组订阅一个 topic 就会收到这个 topic 的全部记录，若仍共用一个 topic，每个组都得读完所有事件再在客户端丢弃。所以现在**每个逻辑消费组一个 topic**：`hmdp.biz.feed`、`hmdp.biz.relations`、`hmdp.biz.media`、`hmdp.biz.cache`、`hmdp.biz.activities`、`hmdp.biz.fulfillment`、`hmdp.biz.orders`、`hmdp.biz.deadlines`，各 3 个分区，另各有一个同分区数的 `.DLT` 死信 topic。发送时 `resolveConsumerGroup(kind)` 先算出组名，再拼成 topic；消费组名为 `hmdp-<group>`。`KafkaMessagingConfig` 用 `NewTopic` Bean 在应用启动时由 KafkaAdmin 创建缺失的 topic，所以 broker 可以保持 `auto.create.topics.enable=false`（本地 compose 就是这样配置的），发往不存在 topic 的记录会在等待元数据超时后发送失败、回到 Relay 退避，而不是悄悄生成一个没人消费的新 topic。

消费端仍会再校验一次：`consume` 要求消息的 kind 解析出来的组等于当前监听的组，不匹配直接视为非法消息。

对 Redis 与文件的外部效果，Inbox 的 MySQL 事务不能自动回滚它们。例如文件已删除但事务提交失败，重试删除应该仍安全。因此需要幂等外部操作、状态校验和读取当前事实，不能把 Inbox 称为所有组件端到端 Exactly Once。

非法、无法解释或冲突消息会记录 `tb_message_failure`，记录 brokerId（`topic-partition@offset`）、逻辑组和原因供复核。不是无限悄悄忽略，也不是把未知消息当成成功业务。

<a id="section-07"></a>

## 7. 乱序如何处理

关注后迅速取消，两个 FOLLOW 事件到达次序可能与发生次序不同。如果直接执行消息中的“关注/取消”动作，旧消息最后到达就会让缓存错。

当前 LIKE/FOLLOW 投影读取数据库当前事实，再更新 Redis，并对相应投影使用锁串行协调。迟到消息会再次投影“现在的真实状态”，而不是机械恢复过去的动作。数据库因此需要保留可查的关系事实。

这不能泛化为所有消息都随便乱序。例如创建、支付、关单需要明确状态条件，分批 Feed 还需要续传游标。要按具体副作用设计幂等与顺序策略。

**Kafka 能提供什么顺序？** 发送时记录的 key 是 `aggregateId`。同一个 key 经过分区器总落到同一分区，而 Kafka 只保证**分区内**有序，所以同一订单、同一笔记、同一店铺的事件在 broker 上按写入顺序排列，监听容器也按这个顺序逐条处理。开启幂等生产者后，客户端内部重试不会让同一分区里的记录重复或调换次序。

但这只是“写入 broker 的顺序”，不是“业务发生的顺序”：Relay 一批里某条发送失败、退避后再发，就可能排在同一聚合后来的事件后面；租约过期后的接管、`redriveUnconsumedEvents` 的重驱动也会让旧事件晚到。不同 key 之间、不同 topic 之间更没有顺序可言。因此上面“读取当前事实”“状态条件更新”的设计仍是正确性的依据，key 有序只是减少乱序的概率，不能替代它们。

<a id="section-08"></a>

## 8. 延迟关单不是 sleep

订单创建事务保存到期时间，并写一条 `ORDER_EXPIRE` Outbox 任务，任务的 `deliver_at` 就是订单的 `expire_at`。

**Kafka 没有单条消息延迟。** RocketMQ 5.x 可以给每条消息设目标投递时间（迁移前用的是 `setDeliverTimeMs`，本地 broker 配置限制在 24 小时以内），broker 到点才让消费者看见。Kafka 的记录一旦写入就对消费者可见，没有“这条 30 分钟后再投”的能力。常见替代方案有按延迟档位拆 topic、消费端暂停等待或借助外部调度；本项目已经有持久 Outbox 表和每秒运行的 Relay，直接复用它们最简单。

现在的做法是**让数据库当定时器**：Relay 每秒领取一批任务，发送前先看 `deliver_at`。如果还在未来，就调用已有的 `deferDeadline`，把任务放回 PENDING 并把 `next_attempt_at` 设为 `deliver_at`，释放租约，不发给 Kafka。由于领取 SQL 只选 `next_attempt_at<=NOW()` 的 PENDING 任务，这条任务在到期前不会再被领取；到点后的下一轮 Relay 才把它发到 `hmdp.biz.deadlines`，消费者再调用数据库条件关单。

```text
订单事务：expire_at = NOW + 30 分钟，Outbox 写 ORDER_EXPIRE(deliver_at = expire_at)
Relay 第一次领取 → deliver_at 在未来 → deferDeadline：PENDING，next_attempt_at = deliver_at
……到期前不再被领取……
到点后 Relay 领取 → 发送到 hmdp.biz.deadlines → 消费组 hmdp-deadlines → closeExpiredOrdinaryOrder
```

这样做的结果：

- 原来“延迟不能超过 24 小时”的限制没有了，超时时间只受业务配置约束（`citylens.reliability.order-timeout-minutes`，代码要求 1～1440 分钟）。
- 未到期任务一直在自己的数据库表里，可以查询、重试，也不会占用 Kafka 的保留期。
- 精度取决于 Relay 的轮询间隔（每秒一次）和积压，不是毫秒级准时；到期判断最终以订单行上的 `expire_at<=NOW(3)` 为准。
- `redriveUnconsumedEvents` 原本就排除未到期任务：带 `deliver_at` 的 SENT 任务要在到期且再过观察窗口后仍未消费，才会被重驱动，未到期任务不会被误判成丢失。

有了 Outbox 定时投递，仍需要数据库兜底：发送失败、消费延迟、服务停机都可能影响及时处理。维护任务按到期索引每分钟处理最多一批约 200 条普通未支付订单。大量积压需要观察批次处理能力，不能认为固定扫描一定立即清空任意数量。

<a id="section-09"></a>

## 9. 不开启 MQ 时是什么行为

MQ 关闭时 Relay 可以通过本地消费服务完成对应任务，定时事件未到期会延后。这便于最小环境学习，也保留任务状态机制。

只有启用实际 Kafka（`hmdp.mq.enabled=true`）、存在 broker 投递与消费者证据，才算验证了真实消息链路。体验页的 Outbox/Inbox 是当前数据库回执，不能仅凭看到了一个表记录就证明 Broker 宕机恢复等全部故障场景都测过。

<a id="section-10"></a>

## 10. 从“业务保存后发消息”推导 Outbox

<a id="section-10-01"></a>

### 两种直接发送顺序都存在窗口

方案 A 先保存数据库，再 send。如果事务提交后 Java 退出，消息没有发出，后续任务永久缺席。方案 B 先 send，再提交数据库。如果消息已经消费，数据库随后回滚，下游执行了没有业务事实的动作。

| 顺序 | 故障发生位置 | 错误结果 |
| --- | --- | --- |
| DB 提交 → send | 提交后 send 前 | 事实存在，后续通知丢失 |
| send → DB 提交 | 消息消费后 DB 回滚 | 下游处理了不存在的事实 |
| DB 与 Outbox 同事务 | 提交后应用退出 | 待办仍在，可以恢复发送 |

Outbox 不要求 send 与 DB 同步成功，而要求“如果业务已提交，发送责任也已经持久保存”。这是本地事务能做到的事情。

<a id="section-10-02"></a>

### 真实事件登记入口

以下为 [BusinessOutboxServiceImpl.java](../src/main/java/com/hmdp/service/impl/BusinessOutboxServiceImpl.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
public void appendBusinessEvent(String kind, long aggregateId, Long userId) {
  if (!TransactionSynchronizationManager.isActualTransactionActive()) {
    throw new IllegalStateException("Outbox 必须与业务写入同事务");
  }
  businessOutboxRepository.insertEvent(kind, aggregateId, userId);
}
```

以下为 [BusinessOutboxRepository.java](../src/main/java/com/hmdp/repository/BusinessOutboxRepository.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```sql
INSERT INTO tb_business_outbox(kind,aggregate_id,user_id)
VALUES(:kind,:aggregateId,:userId)
```

Outbox 的 SQL 都是从迁移前的 MyBatis XML 原样搬进 `@Query(nativeQuery = true)` 的原生语句（`#{x}` 改为 `:x` + `@Param`），写语句带 `@Modifying` 并加入调用方的事务。`BusinessOutboxTask` 只是仓库的领域类型，查询返回行 Map 再经 `fromRow` 转成普通对象，不作为受管实体，所以不会被 JPA 脏检查意外写回。


方法明确检查已有事务。它不是先单独开一个 Outbox 事务再尝试业务，否则一边成功一边失败仍有问题。kind 表示处理类型，aggregateId 是业务对象 ID，userId 保存相关用户上下文。

同一张表承接不同对象，所以 aggregateId=1 可能是店铺 1，也可能是订单 1，要结合 kind。消息查询不能只按 aggregateId 粗暴关联所有任务；体验页限定了相关订单事件类型并校验所有者。

<a id="section-10-03"></a>

### 业务 ID、事件 ID 与 Broker ID

假设订单号为 1001，创建事件的 Outbox id 为 81，Kafka 记录位置为 `hmdp.biz.orders-1@57` 这样的传输标识。它们分别标识订单、处理责任和一次运输，不应互相替代。

同订单可以有创建、付款、关单等多个事件；同事件重发可以产生多条 Kafka 记录（不同 offset）。幂等键应选逻辑事件或业务动作，不能每次重发都使用新 UUID 掩盖重复。

以下为 [BusinessEvent.java](../src/main/java/com/hmdp/messaging/BusinessEvent.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
package com.hmdp.messaging;

import com.hmdp.entity.BusinessOutboxTask;
import lombok.Data;

/** The durable outbox ID is stable across sends; broker message IDs are transport metadata. */
@Data
public class BusinessEvent {
  private int schemaVersion = 1;
  private long eventId;
  private String kind;
  private long aggregateId;
  private long occurredAt;
  private Long deliverAt;

  public static BusinessEvent from(BusinessOutboxTask task) {
    BusinessEvent event = new BusinessEvent();
    event.eventId = task.getId();
    event.kind = task.getKind();
    event.aggregateId = task.getAggregateId();
    event.occurredAt = task.getCreatedAt().getTime();
    if (task.getDeliverAt() != null) {
      event.deliverAt = task.getDeliverAt().getTime();
    }
    return event;
  }
}
```


消息含 schemaVersion、eventId、kind、aggregateId、occurredAt 与可选 deliverAt，以 JSON 作为 Kafka 记录的 value；记录的 key 是 aggregateId，header 另带 `eventId` 和 `kind`，方便排查时不解析 value 就能识别。当前消费者会回数据库核对任务身份，所以这个消息并不是适用于任意独立服务的完整业务快照。拆服务时要考虑接收方是否能访问这张 Outbox。

<a id="section-11"></a>

## 11. 多 worker 怎样领任务又不长时间锁数据库

<a id="section-11-01"></a>

### 查询条件与 SKIP LOCKED

以下为 [BusinessOutboxRepository.java](../src/main/java/com/hmdp/repository/BusinessOutboxRepository.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```sql
SELECT *
FROM tb_business_outbox
WHERE (state='PENDING' AND next_attempt_at<=NOW()) OR (state='SENDING' AND lease_until<NOW(3))
ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
```


PENDING 要到 next_attempt_at 才可执行；SENDING 只有 lease_until 已过期才可接管。按 ID 有限取批，SKIP LOCKED 避开其他事务正在领的记录，不在一把全局大锁上排队。

这是数据库里的短事务领取，不是在数据库事务中一直等待 Kafka 网络返回。高并发下还要观察索引、批次和锁竞争，SKIP LOCKED 不是“所有情况都无锁”。

以下为 [OutboxRelayServiceImpl.java](../src/main/java/com/hmdp/service/impl/OutboxRelayServiceImpl.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
/**
 * 用短事务领取一批可投递任务，写入租约后提交。
 *
 * <p>SKIP LOCKED 允许其他 worker 领取不同任务；网络投递放在提交之后，避免长时间持有行锁。
 */
@Override
public List<BusinessOutboxTask> claimOutboxBatch(String leaseOwner) {
  return transactions.execute(
      tx -> {
        List<BusinessOutboxTask> tasks =
            businessOutboxRepository.selectClaimableEvents(Math.max(1, Math.min(100, batchSize)));
        for (BusinessOutboxTask task : tasks) {
          businessOutboxRepository.claimEvent(leaseOwner, effectiveLeaseSeconds(), task.getId());
        }
        return tasks;
      });
}
```

以下为 [BusinessOutboxRepository.java](../src/main/java/com/hmdp/repository/BusinessOutboxRepository.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```sql
UPDATE tb_business_outbox
SET lease_until=TIMESTAMPADD(SECOND,:leaseSeconds,NOW(3))
WHERE id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
```


owner 是每批唯一 UUID。续期与状态写回都核对 owner，防止同 JVM 两次重叠调用或旧 worker 恢复后覆盖新持有者。

<a id="section-11-02"></a>

### 发送代码怎么分段读

以下为 [OutboxRelayServiceImpl.java](../src/main/java/com/hmdp/service/impl/OutboxRelayServiceImpl.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
/**
 * 领取并投递一批 Outbox 任务，不保证一次清空所有待处理任务。
 *
 * <p>每批使用独立租约 owner，过期或重叠执行时通过 owner 条件避免覆盖新 worker 的结果。
 */
@Override
public void relayOutboxBatch() {
  // 批次独立 owner 防止同进程重叠调用在租约过期后互相覆盖。
  String leaseOwner = UUID.randomUUID().toString();
  for (BusinessOutboxTask task : claimOutboxBatch(leaseOwner)) {
    sendClaimedTask(task, leaseOwner);
  }
}

private void sendClaimedTask(BusinessOutboxTask task, String leaseOwner) {
  long started = System.nanoTime();
  try {
    if (businessOutboxRepository.renewLease(effectiveLeaseSeconds(), task.getId(), leaseOwner)
        == 0) {
      return;
    }
    if (deliverOutboxTask(task, leaseOwner)) {
      metrics.counter("hmdp.mq.sends", "result", "success").increment();
    }
  } catch (RuntimeException failure) {
    recordSendFailure(task, leaseOwner, failure);
    metrics.counter("hmdp.mq.sends", "result", "retry").increment();
    log.warn("事件 {} 投递失败，将按原事件号重试: {}", task.getId(), FailureMessages.summarize(failure));
  } finally {
    metrics.timer("hmdp.mq.send").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
  }
}

private boolean deliverOutboxTask(BusinessOutboxTask task, String leaseOwner) {
  BusinessEvent event = BusinessEvent.from(task);
  // Kafka has no per-message delay: a future deadline stays in the outbox (next_attempt_at =
  // deliver_at) and is published by the relay once due, so the database is the timer.
  if (event.getDeliverAt() != null && event.getDeliverAt() > System.currentTimeMillis()) {
    businessOutboxRepository.deferDeadline(task.getId(), leaseOwner);
    return false;
  }
  if (mqEnabled) {
    publishers.getObject().publish(event);
    // 快速消费可能已经完成；owner/state 条件防止 DONE 被覆盖成 SENT。
    businessOutboxRepository.markSent(task.getId(), leaseOwner);
    return true;
  }
  consumer.consume(event, BusinessEventServiceImpl.resolveConsumerGroup(event.getKind()));
  return true;
}

private void recordSendFailure(
    BusinessOutboxTask task, String leaseOwner, RuntimeException failure) {
  int attempts = task.getAttempts() + 1;
  boolean needsReview = attempts >= DeliveryRetryPolicy.MAX_SEND_ATTEMPTS;
  String reason = FailureMessages.summarize(failure);
  transactions.execute(
      tx -> {
        int changed =
            businessOutboxRepository.recordSendFailure(
                attempts,
                DeliveryRetryPolicy.delaySeconds(attempts),
                needsReview ? OutboxState.REVIEW.name() : OutboxState.PENDING.name(),
                reason,
                task.getId(),
                leaseOwner);
        if (changed > 0 && needsReview && "SECKILL_FULFILL".equals(task.getKind())) {
          seckillReservationRepository.markProcessingForReview(reason, task.getAggregateId());
        }
        return null;
      });
}

private int effectiveLeaseSeconds() {
  return Math.max(10, leaseSeconds);
}
```


`relayOutboxBatch` 创建批次 owner 并领取；`sendClaimedTask` 负责续期、计时与失败处理；`deliverOutboxTask` 负责延期判断以及 Kafka 或本地投递；`recordSendFailure` 在事务中保存退避与复核状态。方法拆开后，租约校验和异常捕获仍包住同一个任务的投递过程。

未到目标时间的 deadline 一律延后：无论是否启用 Kafka，延期判断都在发送之前，任务回到 PENDING 等 `next_attempt_at`。迁移前 MQ 模式会把定时消息提前交给 RocketMQ，由 broker 计时；Kafka 做不到，所以这个判断移到了 `mqEnabled` 分支前面（迁移提交里 Relay 的改动只有这一处）。`publish` 正常返回才写 SENT，而且写入必须带条件，因为此时快消费者可能已经消费完成。

attempts 增加后退避秒数按指数增大并设上限，默认自动发送第 10 次失败后 REVIEW。它避免故障时每秒无穷重试，但也意味着恢复不能只依赖“总有一天自己成功”：复核状态需要被观察和处理。

<a id="section-11-03"></a>

### 发送确认丢失怎样重试

Broker 已收消息，但发送者网络超时没收到确认，任务会回到重试路径。再次发原 eventId 是正确选择；如果为了避免重复而直接标 DONE，就可能在 Broker 实际没收到时永久丢任务。

“确认”在 Kafka 上具体指什么？生产者配置为 `acks=all`：leader 要等所有同步副本（ISR）都写入后才回复成功；`enable.idempotence=true`：生产者给每条记录带序号，客户端内部重试时 broker 能识别并丢弃同一会话里的重复写入，也不会让同一分区的记录乱序。`KafkaBusinessTransport.publish` 调用 `template.send(record).get(sendTimeoutMillis, ...)` **同步等待**这个确认（默认最多 10 秒），正常返回才意味着 broker 已经存下这条记录，Relay 随后才写 SENT；超时、中断或被拒绝都会抛出“Kafka 投递结果未确认”，交给 Relay 原有的退避重试。

要注意幂等生产者的范围：它只去掉**同一个生产者会话内部重试**造成的重复。Relay 因超时重新领取任务、再调用一次 `publish`，对 Kafka 来说是一条新记录，仍会重复，这正是消费端需要 Inbox 的原因。本地 compose 只有一个 broker、副本数为 1，`acks=all` 在这里等价于“写入这唯一一个副本”，不等于获得了多副本容灾。

以下为 [BusinessOutboxRepository.java](../src/main/java/com/hmdp/repository/BusinessOutboxRepository.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```sql
UPDATE tb_business_outbox
SET state='SENT',sent_at=NOW(3),lease_owner=NULL,lease_until=NULL,last_error=NULL
WHERE id=:eventId AND state='SENDING' AND lease_owner=:leaseOwner
```


WHERE 同时要求 SENDING 和正确 owner。任务被快速消费者置 DONE 或被别的批次接管时，旧发送者更新 0 行，不把状态倒退。

<a id="section-12"></a>

## 12. Inbox 与业务状态怎样在同一事务完成

以下为 [BusinessEventServiceImpl.java](../src/main/java/com/hmdp/service/impl/BusinessEventServiceImpl.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
public void consume(BusinessEvent event, String group) {
  if (event.getSchemaVersion() != 1
      || event.getEventId() <= 0
      || event.getKind() == null
      || !group.equals(resolveConsumerGroup(event.getKind()))) {
    throw new IllegalArgumentException("事件版本、事件号或消费组无效");
  }
  long started = System.nanoTime();
  try {
    transactions.execute(
        tx -> {
          consumeLockedTask(event, group);
          return null;
        });
  } finally {
    metrics
        .timer("hmdp.mq.consume", "group", group)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
  }
}

private void consumeLockedTask(BusinessEvent event, String group) {
  // 只锁当前事件；业务效果、Inbox 和任务完成状态仍在同一事务提交。
  BusinessOutboxTask task = businessOutboxRepository.selectEventForUpdate(event.getEventId());
  if (task == null) {
    throw new IllegalArgumentException("消息没有对应的持久化事件");
  }
  if (!event.getKind().equals(task.getKind())
      || event.getAggregateId() != task.getAggregateId()) {
    throw new IllegalArgumentException("消息内容与持久化事件不一致");
  }
  if (messageRepository.countInboxEvent(group, event.getEventId()) > 0) {
    businessOutboxRepository.markDuplicateDone(event.getEventId());
    metrics.counter("hmdp.mq.consumptions", "group", group, "result", "duplicate").increment();
    return;
  }
  if (OutboxState.REVIEW.matches(task.getState())) {
    throw new IllegalStateException("事件等待人工复核");
  }
  if (projector.project(task) == ProjectionResult.CONTINUE) {
    appendFeedContinuation(task);
  }
  messageRepository.insertInboxEvent(group, event.getEventId());
  businessOutboxRepository.markConsumed(event.getEventId());
  metrics.counter("hmdp.mq.consumptions", "group", group, "result", "success").increment();
}

private void appendFeedContinuation(BusinessOutboxTask task) {
  // 游标由当前批次推进；后续批次独立持久化，沿用原发布时间。
  long cursor = businessOutboxRepository.selectCursor(task.getId());
  businessOutboxRepository.insertFeedContinuation(
      task.getKind(), task.getAggregateId(), task.getUserId(), cursor, task.getCreatedAt());
}
```


按以下五步检查：

1. 消息版本、正事件 ID、kind 和逻辑消费组必须匹配。
2. 锁 Outbox 对应行，任务不存在或内容不一致不能继续。
3. 已有 Inbox 表示这个组处理过，走重复路径，不再重做。
4. 执行投影；返回 `ProjectionResult.CONTINUE` 时创建 Feed 续传事件，返回 `COMPLETE` 表示不需要下一批。
5. 插入 Inbox、标任务 DONE，并与数据库效果一同提交。

Feed 续传不是“本次失败”：本批的 Redis 投递完成后，返回 `ProjectionResult.CONTINUE`，事务中登记下一批任务并把当前任务标为 DONE。新增的 `MessagingIntegrationTest.feedContinuationPreservesCursorAndPublicationTimeAcrossDuplicateDelivery` 用 201 名发布前关注的粉丝和 1 名发布后关注的粉丝验证边界：首批只处理 200 人，续批覆盖剩余 1 人；重投首批不会多建续传任务；下一批沿用原发布时间，也不会把历史笔记发给后来才关注的人。

这里的 Inbox 实际在投影之后插入，但同事务内对事件行加锁，并由唯一键兜底。不能机械背“必须先插 Inbox”；关键是数据库处理效果与登记要原子提交，并正确串行化同事件竞争。

<a id="section-12-01"></a>

### 三个消费故障时序

| 故障 | 是否有 Inbox | 重试结果 |
| --- | --- | --- |
| DB 投影失败并回滚 | 没有成功提交 | 后续重新处理 |
| DB 提交后 offset 未提交（进程退出或重平衡） | 有 | 同一记录再次投递，识别重复，不再重复 DB 效果 |
| Redis/文件已改但 DB 回滚 | 可能无 | 外部操作要幂等，再依据当前事实执行 |

第三行是最容易忽略的边界。数据库事务并不管理 Redis 或文件；即使使用 Inbox，仍需要幂等删除、固定 Feed member、读取当前关系与 owner/state 校验。

<a id="section-12-02"></a>

### 怎样决定重试还是隔离

非法格式/未知事件没有合法事实可以执行，要持久隔离；合法事件的暂时故障可以重试。当前有效消费失败达到 5 次（`DeliveryRetryPolicy.MAX_CONSUME_ATTEMPTS`）后进入 REVIEW 并记录失败，秒杀履约还关联预约复核。传输层的最大重试次数与业务主动隔离门槛不是同一个数字。

Kafka 监听器怎样把这个决定落到 offset 上？以下为 [KafkaBusinessTransport.java](../src/main/java/com/hmdp/messaging/KafkaBusinessTransport.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
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
```

监听容器配置为 `AckMode.RECORD`：监听方法**正常返回**，容器就提交这一条记录的 offset；抛出异常则不提交，交给错误处理器。于是有四种结局：

| 情况 | `recordConsumptionFailure` | 监听方法 | offset 与后续 |
| --- | --- | --- | --- |
| 消费成功（含识别为重复） | 不调用 | 正常返回 | 提交，处理下一条 |
| 合法事件失败，投递次数 < 5 | 记 `consume_attempts`，返回 false | 重新抛出 | 不提交，`DefaultErrorHandler` 退避后重投同一条 |
| 合法事件第 5 次失败，或消息非法/与 Outbox 行不匹配 | 写 `tb_message_failure`，合法事件标 REVIEW，返回 true | 正常返回 | 提交，隔离完成，分区继续前进 |
| 连失败状态都写不进数据库 | 抛异常 | 重新抛出原错误 | 不提交，继续退避；错误处理器重试预算用完后发到 `<topic>.DLT` |

几个细节：

- **投递次数从哪来**：容器开启了 `deliveryAttemptHeader`，每次重投时把次数写进记录 header（`KafkaHeaders.DELIVERY_ATTEMPT`），`deliveryAttempt(record)` 读出后交给业务层判断是否达到 5 次。迁移前这个数字来自 RocketMQ 的重消费次数（`getReconsumeTimes() + 1`），判断逻辑没有变。
- **退避**：`DefaultErrorHandler` 用 `ExponentialBackOff(500, 2.0)`，从 0.5 秒起翻倍，单次最长 10 秒，最多 15 次（`MAX_LISTENER_RETRIES`）。这是传输层的兜底预算，比业务层的 5 次大，正常情况下业务层会先隔离。
- **重试会阻塞同一分区**：错误处理器把分区位置退回到失败记录再重投，所以重试期间这个分区后面的记录要等待。这保留了分区内顺序，也说明为什么要在有限次数内隔离，而不是让一条坏消息长期堵住分区。
- **DLT 是最后一道保险**：只有失败本身无法持久化（例如数据库一直不可用）且重试预算耗尽时，记录才由 `DeadLetterPublishingRecoverer` 发到同分区号的 `<topic>.DLT`（例如 `hmdp.biz.orders.DLT`），随后 offset 被提交。这时 Outbox 行仍不是 DONE，SENT 超时后的 `redriveUnconsumedEvents` 还会沿原 eventId 重新投递，所以 DLT 用于排查，不是唯一的恢复依据。
- **非法消息不能冒用别人的 eventId**：载荷无法解析、kind/aggregateId 与 Outbox 行不一致时，按 brokerId（`topic-partition@offset`）隔离，不会把那个 eventId 对应的真实任务改成 REVIEW。

失败记录没有保存成功时，不应返回消费成功；否则日志虽然写了一句错误，消息责任却没了。`KafkaBusinessTransport` 的监听方法只有在消费成功、或失败已持久保存并被允许隔离后，才正常返回、让容器提交 offset。

<a id="section-13"></a>

## 13. 定时关单为什么还需要数据库扫描

以下为 [KafkaBusinessTransport.java](../src/main/java/com/hmdp/messaging/KafkaBusinessTransport.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
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
```

以下为 [OrderLifecycleServiceImpl.java](../src/main/java/com/hmdp/service/impl/OrderLifecycleServiceImpl.java) 的相关源码片段。为便于阅读只展示当前部分，依赖与 import 请查看原文件；无需把片段另建类复制运行。

```java
/**
 * 锁定订单并重新校验当前状态、类型和截止时间，再条件关闭普通券订单。
 *
 * <p>提前到达的消息只重新登记截止事件；支付或关闭已经完成时不再产生重复关闭事件。
 */
@Override
public boolean closeExpiredOrdinaryOrder(long orderId) {
  return Boolean.TRUE.equals(
      transactions.execute(
          tx -> {
            List<Map<String, Object>> rows =
                voucherOrderRepository.selectExpiryStateForUpdate(orderId);
            if (rows.isEmpty()) {
              return false;
            }
            Map<String, Object> row = rows.get(0);
            if (!OrderStatus.UNPAID.matches(((Number) row.get("status")).intValue())
                || row.get("voucher_type_snapshot") == null
                || !VoucherType.ORDINARY.matches(
                    ((Number) row.get("voucher_type_snapshot")).intValue())) {
              return false;
            }
            if (row.get("expire_at") == null) {
              throw new IllegalStateException("普通订单缺少截止时间");
            }
            long userId = ((Number) row.get("user_id")).longValue();
            if (((Number) row.get("due")).intValue() == 0) {
              outbox.appendOrderExpirationEvent(
                  orderId, userId, (Timestamp) row.get("expire_at"));
              return false;
            }
            if (voucherOrderRepository.closeExpiredOrder(orderId) == 0) {
              return false;
            }
            registerOrderClosedEvent(orderId, userId);
            return true;
          }));
}
```


发送方法按 kind 算出逻辑组，再拼成 `hmdp.biz.<group>`；ORDER_EXPIRE 属于 deadlines 组，所以到期任务进 `hmdp.biz.deadlines`。key 用 aggregateId，header 带 eventId 和 kind，然后同步等待 broker 确认。这里**没有任何延迟参数**：能走到 `publish` 的到期任务，已经由 Relay 判断过 `deliver_at` 到点。迁移前的 RocketMQ 版本在这里设置 `setDeliverTimeMs`，并拒绝超过 24 小时的延迟；现在计时完全在 Outbox 表里，这个限制也随之删除，超时配置只受 `OrderLifecycleServiceImpl` 自己的 1～1440 分钟校验约束。

到期消费再锁订单，检查仍是普通、未支付、确实到期。提前收到时重新登记 deadline 而不是无条件关闭；已支付或已取消时不做相反迁移。数据库扫描使用同一个 expire 业务规则，所以两条触发路径可以重复而不重复关单。

<a id="section-13-01"></a>

### 并发付款与关单演算

A 付款与 B 关单同时争取订单状态。先成功把状态从 1 改走的事务提交后，另一个必须看当前状态不能满足，再返回对应结果。不能允许 A 刚付完款，B 用自己几秒前的“未支付”快照无条件取消。

<a id="section-13-02"></a>

### 本章验收模板

正常投递看任务 PENDING/SENT/DONE 和 Inbox；重复投递看 Inbox 不重复与业务效果不变；Broker 短时不可用看任务保留/退避；到期任务看期限和最终订单状态；等待复核看失败原因与受控重试。

每项至少关联 eventId、aggregateId、业务状态和处理时间。指标 `hmdp.mq.client.started=1`（体验页的 clientStarted）只能证明客户端初始化，不能替代这些任务级证据。

经过真实 broker 的证据由 [KafkaBusinessIntegrationTest](../src/test/java/com/hmdp/messaging/KafkaBusinessIntegrationTest.java) 提供，它通过 Testcontainers 启动真实 Kafka（`apache/kafka:3.9.1`）、MySQL 和 Redis：

| 测试 | 验证什么 |
| --- | --- |
| `realBrokerAcceptsAndDeduplicatesRepeatedDelivery` | 同一事件连发 5 次，`orders` 组只有一条 Inbox、一条订单事件历史，任务为 DONE |
| `realTimerClosesUnpaidOrderAndPreservesPaidOrder` | 未到期的 ORDER_EXPIRE 留在 Outbox 为 PENDING；到点后未支付订单被关闭（status=4），已支付订单的到期事件 DONE 但订单仍是已支付 |
| `luaStreamHandoffAndKafkaFulfillmentRemainIdempotent` | Lua → Stream → 持久交接 → `hmdp.biz.fulfillment` 履约，重放与重发都只产生一单 |
| `eventThatDoesNotMatchItsOutboxRowIsQuarantinedNotRetriedForever` | aggregateId 被篡改的消息按 `hmdp.biz.orders-…` brokerId 隔离进失败表，不写 Inbox，原任务不被标 DONE |

租约并行领取、确认丢失后同 ID 重发、快消费者先于 SENT、重驱动、付款与关单竞争等不依赖 broker 的规则，在 [MessagingIntegrationTest](../src/test/java/com/hmdp/messaging/MessagingIntegrationTest.java) 中覆盖。两者都随 `./mvnw verify` 运行，只需要本机 Docker 在运行。

迁移后在本地 compose 上做过一次端到端验证：普通券下单后 ORDER_CREATED 经 Kafka 消费为 DONE 并登记 Inbox；把订单到期时间改到 5 秒后，订单自动变为已关闭（status=4），ORDER_CLOSED 事件也为 DONE。

<a id="section-14"></a>

## 14. 页面与运维怎么观察

普通用户在体验页查看自己的订单，能看到关联业务任务状态和 Inbox 回执数量，不能读取其他用户消息载荷。管理员通过 `/ops/outbox`、`/ops/messages/failures` 查看整体积压与失败，并对指定任务执行 `/ops/outbox/{id}/retry`。

处理顺序：核对事件 kind 与对象 → 判断业务是否已经完成 → 查看发送/消费时间与原因 → 修复组件或数据条件 → 受控重试原 ID → 确认 DONE 与最终业务结果。不能靠创建一个新 eventId 掩盖旧任务，也不能直接把数据库 state 手改成 DONE 作为“修复”。

需要看 Kafka 本身时（本地 compose）：

```bash
# 列出 topic，应能看到 hmdp.biz.<group> 与对应的 .DLT
docker compose -f dev/docker-compose.yml exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
# 查看某个消费组每个分区的提交位置与 lag
docker compose -f dev/docker-compose.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group hmdp-orders
```

lag 持续增长说明消费跟不上或卡在某条记录的重试上；`.DLT` 里出现记录说明有失败连状态都没能写进数据库，要先查数据库连通性，再沿 header 里的 eventId 回到 Outbox 行。指标名 `hmdp.mq.sends`、`hmdp.mq.consumptions`、`hmdp.mq.send`、`hmdp.mq.consume`、`hmdp.outbox.pending/review/inflight` 迁移前后保持不变，告警文字已改为 Kafka。

<a id="section-15"></a>

## 15. 一句话串起这个亮点

“通过业务事务 Outbox 避免提交后任务丢失，Relay 用短行锁与 owner 租约并行领任务，Kafka 以 acks=all 加幂等生产者确认写入、按 aggregateId 分区保序、处理完逐条提交 offset，实现至少一次投递；消费端 Inbox 和业务状态条件防重复，并通过重投、失败隔离、死信 topic、管理接口与指标形成恢复闭环。Kafka 没有单条延迟消息，到期关单由 Outbox 表计时。”

追问保证范围时要指出：保障依赖持久数据库与恢复策略，外部 Redis/文件效果通过幂等协调，单机 Broker、单副本 topic 与本地卷并不具备生产多副本容灾。也可以说明迁移经历：从 RocketMQ 换到 Kafka 时 Outbox/Inbox 的登记与消费逻辑没有改，只替换了 `EventPublisher` 实现、新增 topic 声明，并把 Relay 的延期判断挪到发送之前，这正是把可靠性放在数据库层而不是绑定某个 MQ 特性的好处。

<a id="section-16"></a>

## 16. 可靠消息教程：从一条任务推导发送与消费

<a id="section-16-01"></a>

### 先选一条事件，给三个 ID 各留一栏

在普通订单创建流程中，订单号标识业务对象，Outbox ID 标识这一条要处理的事件，Kafka 记录位置（`topic-partition@offset`）标识一次网络投递。一个订单可有创建、支付、关闭等多条事件；同一事件又可能被多次投递。因此三栏不能合并。

排查时先从订单找到关联任务，再用任务 ID 追踪消费。仅拿 offset 去订单表查询不会找到同样的对象；重新投递也不应生成新的业务 eventId，否则 Inbox 失去识别重放的依据。

当前消费者不仅检查 eventId，还比较事件 kind、aggregateId 与持久任务，并验证 schemaVersion 和消费组。消息内容错配不能因“ID 已存在”就随意当成功，也不能借一个伪造 ID 把别人的真实任务改成 REVIEW。

<a id="section-16-02"></a>

### 逐步推导任务状态，而不是背一串枚举

PENDING 是待领取；SENDING 是某个 worker 已取得租约；SENT 是发送已确认但后续消费可能没结束；DONE 是该逻辑处理步骤持久完成；REVIEW 是自动重试不能继续推进、需要核对。

```mermaid
stateDiagram-v2
    [*] --> PENDING: 持久登记任务
    PENDING --> SENDING: worker 领取并写租约
    SENDING --> PENDING: deliver_at 未到，延后到 deliver_at
    SENDING --> SENT: Kafka acks=all 确认且状态仍匹配
    SENDING --> DONE: 消费者提前完成
    SENT --> DONE: 消费持久完成
    SENDING --> PENDING: 发送失败后退避
    SENT --> PENDING: 超过消费观察窗口重驱动
    SENDING --> REVIEW: 达到重试限制
    SENT --> REVIEW: 消费失败被隔离
    REVIEW --> PENDING: 修复原因后授权重试
```

这是启用 Kafka 路径的概念图，省略了终态的重复投递自环；关闭 MQ 后本地投影可从 SENDING 直接完成。真实迁移仍以 `BusinessOutboxRepository` 中各条 SQL 的状态和 owner 条件为准。

状态不是只由一个线程写。发送 worker 可能正在等待 Kafka 确认，而消费者已经快速完成数据库处理并写 DONE。发送 worker 随后执行 `markSent` 时，SQL 要求旧状态仍是 SENDING 且 owner 匹配，避免把 DONE 覆盖回 SENT。

这个例子说明状态机不只是画箭头。每次 UPDATE 的 WHERE 条件就是防止旧线程覆盖新进度的实际约束；阅读时把状态条件、owner 条件和行数判断一同圈出来。

<a id="section-16-03"></a>

### 多 worker 并行时，租约保护什么

worker A 在短事务中用 `FOR UPDATE SKIP LOCKED` 领取一批，写 owner=A 和 lease_until 后提交。worker B 可以跳过 A 当时锁住的任务，继续领取其他任务。网络发送放在事务外，避免 Broker 慢时长期占住数据库行锁。

假设 A 退出，租约过期后 B 重新领取，owner 改为 B。A 又迟到恢复时，不能凭旧快照把任务结果覆盖回去；续租、发送完成与失败登记都按 owner 条件处理。这与分布式锁的 owner 思想相同，但这里是数据库待办租约。

租约不保证 Broker 只收到一次。A 可能已经发送但未记录确认，B 会再次发同一事件。它保证“可以恢复并限制旧执行者改状态”，重复投递仍由消费端幂等处理。

<a id="section-16-04"></a>

### 用三次退出演算 Inbox 的价值

| 退出位置 | 下次可能发生什么 | 正确依赖 |
| --- | --- | --- |
| 数据库业务与 Outbox 尚未提交 | 事务回滚，没有已提交业务与待办 | 同库事务原子性 |
| Broker 接收后，发送端未记 SENT | 原 eventId 再次投递 | 消费端身份校验与去重 |
| 消费数据库提交后，offset 尚未提交 | 消费者再次收到同一事件 | Inbox 记录与业务结果同事务 |

Inbox 主键包含 consumer_group 和 event_id：同一逻辑组的重放应去重，另一组的独立处理不应被第一组屏蔽。当前任务会校验预期消费组，不是所有任意组都能合法接收同一任务。

还要区分数据库副作用与外部副作用。Inbox 与订单事件历史能同事务提交；Redis ZADD、缓存失效和文件删除不会因 SQL 回滚撤销。外部动作需要能安全重复或重新读取当前事实。不能单凭 Inbox 就把所有跨组件效果宣传成 exactly-once。

<a id="section-16-05"></a>

### 失败重试为什么要有退避与隔离

瞬时连接异常可能恢复，原身份重试合理；无效载荷、缺失归属或内容冲突可能每次都失败，无限快速重试会挤占正常任务。当前设计记录错误摘要、次数与下一次尝试时间，超过策略限制进入 REVIEW 或持久失败记录。

隔离不是忘记任务，而是保存“哪一个对象、什么错误、何时停止自动推进”的证据。人工重试前应先修正原因，之后沿原 ID 恢复；不能只清空失败次数就声称问题已解决。当前默认策略和限额以 `DeliveryRetryPolicy` 为准，业务错误与网络错误需要从原因字段进一步判断。

<a id="section-16-06"></a>

### MQ 开关关闭时，什么仍然成立

关闭 MQ（`hmdp.mq.enabled=false`）后，Relay 在 Java 中调用相同逻辑投影，仍利用持久 Outbox 和 Inbox；未来截止任务同样先延期等待（这一步现在与 Kafka 模式共用同一段代码）。这样可以学习业务设计，但没有实际经过 broker 的网络发送、重投递、消费组和 offset 提交过程。

因此实验记录至少注明 MQ enabled、消费客户端状态、任务 ID、最终数据库效果。真实 Kafka 测试（`KafkaBusinessIntegrationTest`）与本地投影测试（`MessagingIntegrationTest`）是两类证据。不要因两者最终页面都能展示就说真实消息中间件已验证。

<a id="section-16-07"></a>

### 实操验收分成“投递”和“完成”两栏

创建普通订单后保存关联事件。第一次记录任务当前状态；稍后查询，检查消费者是否写入 Inbox 与订单事件历史。ORDER_EXPIRE 在到期前保持 PENDING（`next_attempt_at` 等于到期时间）是预期，它还没有发给 Kafka；不要把这类未到期 PENDING 统计为发送积压。到期后它才会经历 SENDING → SENT → DONE。注意指标 `hmdp.outbox.pending` 统计的是 state 为 PENDING/SENDING 的全部任务（`countPendingEvents`），迁移后会把这些未到期的关单任务也算进去；告警 `BusinessEventsBacklog` 的条件是该值超过 200，同一时段待支付普通订单较多时，要结合 `next_attempt_at` 判断是真积压还是在等到期。

若隔离实验需要观察重驱动，保持原 eventId 并核对最终业务效果只发生一次。仅观察发送日志新增两条不能证明幂等；应比较任务最终 DONE、Inbox 唯一记录及业务结果。[第 13 章](13-测试监控与故障排查.md)进一步说明如何把这类预期写成断言。
