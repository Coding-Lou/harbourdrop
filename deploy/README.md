# 阿里云初步体验部署：2GB 轻量版

适用的已确认环境：Alibaba Cloud Linux 3、x86_64、约 2GB 内存、40GB 系统盘，Docker 26.1.3 / Compose 2.27.0。目标是先让别人通过浏览器体验当前点评项目。

## 1. 这套部署运行什么

运行 Nginx 桌面网页、Java（Java 21 运行镜像 `eclipse-temurin:21-jre`）、MySQL、Redis。后端使用当前源码编译的可执行 JAR（Spring Boot 3.5），前端使用当前 `frontend` 静态页面，所有网页请求仍走同域 `/api`。技术栈迁移后，这套部署不再包含 memcached 容器。

邮箱验证码登录、店铺/搜索、签到、社区、普通券与订单、缓存采样可以体验。秒杀需要管理员准备并发布活动，走 Redis Stream 和本地履约。持久 Outbox/Inbox 仍用于本地投影（业务消息关闭时，Relay 在本进程直接调用同一套消费逻辑）。免费券的“已领取”状态不表示真实收款，模拟支付默认关闭。

这台 2GB 机器先关闭 Kafka 和 CDC（Debezium）。体验页会显示“业务消息（Kafka）”“CDC 连接（Debezium）”等功能未启用；不能据此宣称已验证真实消息中间件。四层缓存（请求内缓存 → Caffeine → Redis → MySQL）实现保留，具体采样命中哪一层取决于当时状态。数据库直改不再由 CDC 自动传播，演示更新请使用业务接口。

限额用于约束容器和 JVM，并不保证任何负载都能运行在 2GB；首次启动和实际体验后需检查内存与 OOM。当前只做小范围试用，没有接入真实邮件发送（SMTP）、支付或多机容灾。

## 2. 本地打包，再上传服务器

本地 Mac 终端在 Java 项目根目录执行；这些命令不是在云端 SSH 中执行：

```bash
cd harbourdrop  # 项目根目录
# 需要本机 JDK 21；项目自带 Maven Wrapper。跳过业务测试，但保留 Checkstyle 构建检查
./mvnw -Dmaven.test.skip=true package
python3 deploy/package-release.py
```

本机没有 JDK 21 时，可在 Docker 中用 Java 21 的 Maven 镜像构建，并复用已经存在的开发 Maven 缓存：

```bash
docker run --rm -v "$PWD:/workspace" \
  -v harbourdrop_maven-cache:/root/.m2 -w /workspace \
  maven:3.9-eclipse-temurin-21 mvn -Dmaven.test.skip=true package
python3 deploy/package-release.py
```

生成 `target/hmdp-cloud-release.tar.gz`。包内只有可执行 JAR、前端、初始数据库 SQL、初始图片、云端配置与校验清单，不包含 `.env`、SSH 密钥、IDE 状态或本机数据库备份。它建立新的演示环境，不会把本机现有订单/用户/库存整体迁过去。

通过阿里云远程连接工具的文件上传功能，把这个包传到服务器 `/root`。也可在 Mac 终端使用：

```bash
scp target/hmdp-cloud-release.tar.gz root@101.132.47.134:/root/
```

当前云端登录工具生成的临时 SSH 密钥不一定被本机 scp 自动使用。需要额外认证时，使用你已有的文件上传功能或自己已有的 SSH 凭证；不必把密码/私钥发给助手。初次独立连接还要确认服务器主机指纹。

## 3. 服务器端解压和创建配置

以下在已经登录的云服务器终端中执行。首次安装到 `/opt/hmdp-cloud`，如目标目录已经有别的部署，先核对归属，不直接覆盖。

```bash
mkdir -p /opt/hmdp-cloud
tar -xzf /root/hmdp-cloud-release.tar.gz -C /opt/hmdp-cloud --strip-components=1
cd /opt/hmdp-cloud
sh init-env.sh
docker compose --env-file .env -f compose.yml config --quiet
```

`init-env.sh` 生成独立随机的数据库 root、业务数据库、Redis、监控凭证和 JWT 签名密钥 `HMDP_JWT_SECRET`（`openssl rand -hex 32`），文件权限为 600；已有 `.env` 时不覆盖。后端以 `hmdp_app` 连接数据库，不使用 root。首次创建数据库卷时导入课程初始 SQL，随后 Java 自动执行已有 Flyway 增量迁移（包括把登录身份改为邮箱的 V10）。

**从迁移前版本升级时必须手动补 JWT 密钥。** `HMDP_JWT_SECRET` 是新增的必填项，compose.yml 用 `${HMDP_JWT_SECRET:?...}` 强制要求；但 `init-env.sh` 看到已有 `.env` 会直接退出、不会追加新变量。所以旧服务器上要手动追加一次（已存在时不重复写）：

```bash
cd /opt/hmdp-cloud
grep -q '^HMDP_JWT_SECRET=' .env || printf 'HMDP_JWT_SECRET=%s\n' "$(openssl rand -hex 32)" >> .env
docker compose --env-file .env -f compose.yml config --quiet
```

这个密钥签发和校验所有 access token，至少 32 字节，和其他凭证一样不要外传；更换它会让已签发的 access token 全部失效，用户需要重新登录。旧 `.env` 里残留的 Memcache 相关变量不再被使用，可以保留也可以删除。

不要删除 `.env` 再生成密码后继续连接旧数据库卷：旧库密码不会因此自动更新。升级保留 `.env` 和三个数据卷。配置检查使用 `--quiet`，避免把展开的凭证输出后复制到聊天或分享材料中。

## 4. 构建并启动

```bash
cd /opt/hmdp-cloud
docker compose --env-file .env -f compose.yml up -d --build
docker compose --env-file .env -f compose.yml ps -a
docker compose --env-file .env -f compose.yml logs --tail=100 java
curl -i 'http://127.0.0.1:8080/api/shop-type/list'
```

构建只把预编译 JAR 放入 Java 21 运行镜像（Dockerfile 默认 `eclipse-temurin:21-jre`），云端无需重新运行 Maven。首次仍需下载基础镜像。若 Docker Hub 拉取失败，要使用你有权限的镜像仓库/加速配置，或通过可信本机 `docker save` 上传所需镜像；不要随意替换为来源不明的项目镜像。

`image-init` 的 Exited(0) 表示初始图片复制完成，是正常状态。复制不覆盖已有同路径图片，后续上传数据保存在 uploads 卷。web 的健康检查经过 `/api/actuator/health`，等待 Java 启动后才能 healthy。

网页端口为 `0.0.0.0:8080`；Java 8081 仅绑定服务器本机；MySQL、Redis 不发布宿主端口。公网访问通过 Nginx，管理运维 `/api/ops/` 和 Prometheus 路由不在该入口公开。Nginx 重写 X-Forwarded-For，Java按 native策略恢复访客地址，避免所有人被简单当成同一个代理 IP限流。

## 5. 阿里云安全组开放网页端口

已提供的安全组是 `sg-uf6g6341ywlxvz9zennd`，之前的入方向规则没有网页端口。进入 ECS 实例 → 安全组 → 入方向 → 添加规则：

| 字段 | 本次配置 |
| --- | --- |
| 协议 | TCP |
| 端口范围 | 8080/8080 |
| 授权对象 | 小范围试用填体验者公网 IP/CIDR；需要公开网页时才使用 0.0.0.0/0 |
| 描述 | 黑马点评网页体验 |

不需要开放 3306、6379、8081 或 Kafka 端口。安全组放行与服务器防火墙是两层检查；如果 firewalld已启用，需在实际生效的 zone中放行网页端口，不直接关闭整个防火墙。

```bash
# 只读判断当前防火墙状态和生效 zone
systemctl is-active firewalld
firewall-cmd --get-active-zones
```

Docker的端口发布与防火墙规则还取决于实际网络配置。以安全组和从外部浏览器实际访问的结果为准，不将“本机 curl成功”当成公网已开放。

来源：[阿里云 Docker 部署说明](https://help.aliyun.com/zh/document_detail/2842585.html)、[Docker 端口发布](https://docs.docker.com/engine/network/port-publishing/)。

### 本机已遇到的容器互联问题

2026-10-11 云端首次启动时，Java 报 `No route to host`，不能连接已经健康的 Redis。实际检查发现 firewalld 正在运行，但点评的 Docker 桥接接口没有被关联到允许容器通信的 zone；iptables 的转发规则存在，firewalld 的另一组规则仍然拒绝了流量。这个错误发生在服务器内部，与阿里云是否放行公网 8080 是两个问题。

本次只将点评项目自己的桥接接口加入现有 `trusted` zone，并在绑定 eth0 的 `public` zone 放行 8080。没有关闭 firewalld，也没有开放数据库宿主端口。下列命令仅适用于相同诊断结果；正常通信的服务器无需照抄：

```bash
bridge_id=$(docker network inspect hmdp-cloud_default --format '{{.Id}}')
bridge_name="br-$(printf '%s' "$bridge_id" | cut -c1-12)"
ip link show "$bridge_name"
firewall-cmd --zone=trusted --add-interface="$bridge_name"
firewall-cmd --permanent --zone=trusted --add-interface="$bridge_name"
firewall-cmd --zone=public --add-port=8080/tcp
firewall-cmd --permanent --zone=public --add-port=8080/tcp
docker compose --env-file .env -f compose.yml restart java
```

`trusted` 对关联接口放行流量，所以只绑定已核对的点评桥接接口，不能把公网网卡 eth0 放进去。规则同时写入当前运行配置和永久配置，本次没有执行全局防火墙 reload。后续 `down` 会删除 Compose 网络，再次 `up` 时桥接接口可能改变，需重新核对并更新这条接口关联，移除不再使用的旧接口记录。不要把首次部署时的接口名称写死。

Docker 的标准 firewalld 集成会为 Docker 桥接接口创建专用 zone。换服务器或调整 Docker 配置时，应先检查实际集成状态，再决定是否需要上述兼容处理。参考：[Docker 与 firewalld 的集成](https://docs.docker.com/engine/network/packet-filtering-firewalls/#integration-with-firewalld)。

## 6. 最终访问与正常登录

网页：`http://101.132.47.134:8080/`。

体验页：`http://101.132.47.134:8080/experience.html`。

先在自己的电脑浏览器打开，核对真实店铺数据、图片、筛选和缓存采样。课程店铺图片有第三方 URL，并非全部是本地文件；这些图片依赖外站可用性，页面已有加载失败占位图。本地分类、笔记图片和前端资源已打包。再让一位外部体验者打开，确认安全组范围和公网链路。

登录方式是**邮箱 + 一次性验证码**。这台小机器没有 SMTP，所以 compose.yml 里明确设置了 `HMDP_LOGIN_CODE_LOG` 默认 `true`：验证码不会真正发到邮箱，而是写进 Java 日志（邮箱打码，如 `u***@example.com`）。这个默认值**只适用于本私有演示机**；应用本身默认关闭该开关，没有 SMTP 时直接让发送失败。

试用者在登录页输入邮箱并点获取验证码后，部署者在服务器读取对应时段的日志并提供验证码：

```bash
docker compose --env-file .env -f compose.yml logs --since=2m java | grep 'login code'
```

日志里的邮箱是打码的，多人同时体验时容易对不上，请按账号依次操作。不要公开整个日志，也不要共享自己的 access/refresh token。种子用户的邮箱是 `user<id>@example.com`（如 `user1@example.com`）；输入新邮箱会在首次登录时自动注册。

在两分钟内用验证码完成登录；正常登录后可看本人资料、签到、领券、订单和发布笔记。access token 默认 15 分钟有效，前端过期后会用 refresh token 自动续期。公开体验不依赖登录的部分包括店铺、搜索和缓存观察。本部署没有偷偷加入登录绕过。

若希望访客自己收验证码，要改用真实 SMTP，并关闭日志开关：

1. 在 `.env` 中填写邮件服务商提供的 `SPRING_MAIL_HOST`、`SPRING_MAIL_PORT`（默认 587）、`SPRING_MAIL_USERNAME`、`SPRING_MAIL_PASSWORD` 和发件人 `HMDP_MAIL_FROM`。`compose.yml` 已经把这些变量传给 Java，默认开启 STARTTLS（`SPRING_MAIL_STARTTLS=false` 可关闭）。SMTP 密码只放在 `.env`，不要写进 compose 文件。
2. 同样在 `.env` 中设置 `HMDP_MAIL_ENABLED=true`、`HMDP_LOGIN_CODE_LOG=false`。邮件服务不可用不会让健康检查变成 DOWN，但获取验证码会直接失败，日志不会出现验证码。
3. 执行 `docker compose --env-file .env -f compose.yml up -d --no-deps java` 重建 Java 容器，用自己的邮箱试一次。

当前 IP + HTTP入口适合有限的初步演示。正式长期分享前应配置域名与 HTTPS，保护登录凭证传输；涉及域名和服务器区域的备案要求，按阿里云当前规则另行确认。本配置未自动申请证书。

## 7. 管理员与模拟支付

先正常登录，在浏览器 Network 查看 `/api/user/me` 返回的真实 `data.id`。编辑 `/opt/hmdp-cloud/.env` 的 `HMDP_ADMIN_IDS`（容器内映射为 `CITYLENS_CATALOG_ADMIN_IDS`），多个 ID英文逗号分隔。管理员身份不写进 token，每次请求按这个列表计算，所以从列表中删除 ID 后（重建容器）立即失去管理权限。需要模拟支付演示时设置 `HMDP_PAYMENT_MOCK_ENABLED=true`，不要将模拟交易解释为真实支付。

```bash
cd /opt/hmdp-cloud
vi .env
docker compose --env-file .env -f compose.yml up -d --no-deps java
```

重建容器才能更新环境变量，`restart` 不会重新读取 .env。管理员可准备商品；运维接口从服务器 localhost:8081或自己的 SSH隧道访问，仍要求业务管理员身份，请求头为 `Authorization: Bearer <accessToken>`。

## 8. 内存、运行状态与日志

```bash
docker compose --env-file .env -f compose.yml ps -a
docker stats --no-stream
free -h
df -h /
docker compose --env-file .env -f compose.yml logs --tail=100 mysql java web
```

Java堆上限 256MB，容器上限512MB；MySQL上限512MB并缩小缓冲区，Redis上限192MB且禁用自动淘汰业务 Key，Nginx上限64MB。这是初始体验预算，应用的原生内存、线程、数据库初始化峰值和宿主进程都要算在内。

如果容器重启，检查是否 OOMKilled，不先用反复重启掩盖。Redis达到 maxmemory后会拒绝新增写入，不能为腾空间使用 FLUSHALL或随机删秒杀/签到数据。进一步并发演示、全部中间件或更大数据量需要重新评估资源，本文件不保证2GB可以承载所有亮点与压测。

日志轮转每容器最多3份10MB文件，避免长期运行的普通日志无界增长。数据库、Redis和图片仍是持久数据，需要按实际增长规划备份。

## 9. 日常停止与更新

```bash
# 保留数据卷与 .env
docker compose --env-file .env -f compose.yml down
# 继续使用现有数据
docker compose --env-file .env -f compose.yml up -d
```

不要在保存了体验数据的环境使用 `down -v`。更新前备份数据库、Redis持久数据、uploads与 .env；新包中的SQL只用于首次数据库卷初始化，不要手工再导入覆盖旧库。

重新编译打包后，将新包上传并更新运行文件，保留已有 .env和数据卷，再执行 `up -d --build`。这是就地升级，不是零停机滚动发布；Flyway执行结构迁移前应具备可恢复备份。

从迁移前版本（Java 8、带 memcached）升级到当前版本时，额外注意：

- 先按第 3 节给 `.env` 追加 `HMDP_JWT_SECRET`，否则 Compose 配置检查直接失败。
- 新 compose.yml 已没有 memcached 服务，旧容器会成为孤儿容器，升级时可用 `docker compose --env-file .env -f compose.yml up -d --build --remove-orphans` 一并移除。
- Flyway 会执行 V10：给 tb_user 加 email 唯一列，并为已有用户补 `user<id>@example.com`。原来用手机号登录的演示用户之后要用这个邮箱登录，`/user/me` 可查到自己的 id。
- 旧的随机 token 登录态不再有效，所有人需要重新登录一次。
- Redis 里旧格式的缓存值无法解析时会被失效并直接回源，下一次读取按新格式重建，不需要手工清缓存。

## 10. 部署验收记录与最小验收清单

> 以下 2026-10-11 的记录都属于**技术栈迁移前**的版本（Java 8 运行镜像、手机号验证码登录、带 memcached），保留为历史记录，不代表迁移后的版本已在云端验收。迁移后升级或重新部署，请按本节末尾的最小验收清单重新检查。

2026-10-11（迁移前）：当时源码 Maven package成功，Checkstyle 0违规；Compose与 Nginx语法检查通过；随机凭证生成、600权限及重复调用不覆盖已验证。部署包使用独立 `hmdp-cloud-smoke` 项目做本地启动验收：数据库自动初始化/迁移、业务数据库账号、Redis密码、代理查询、静态图片、两次缓存读取、正常验证码登录、签到及公开运维路径拒绝均通过。

本地验收复用缓存 Maven/Temurin Java 8镜像执行相同 JAR（迁移前版本）。测试容器与临时数据已单独清理。

2026-10-11 13:50（北京时间，迁移前）：部署包上传至 `101.132.47.134` 后，SHA-256 校验通过，已解压到 `/opt/hmdp-cloud`。云端成功下载默认 `eclipse-temurin:8-jre`，完成镜像构建、MySQL 初始化、应用启动和内部防火墙修复。通过服务器本机 Nginx 入口验证了首页、体验页、优惠券页、本地图片、分类、店铺列表/详情、两次缓存读取、正常验证码登录、本人资料、签到，以及匿名和公开运维路径拒绝。MySQL、Redis、web 均 healthy；验收时运行容器没有 OOMKilled。

验收按当时的手机号登录流程创建了手机号 `18800001234` 对应的普通演示用户并签到一次（升级执行 V10 后，该用户的登录邮箱会变为 `user<id>@example.com`），没有配置管理员权限。测试不会证明完整领券、秒杀、支付流程或消息中间件链路正常，也没有进行浏览器视觉验收或并发压测。

首次公网检查时（迁移前），8080 从本地电脑两次连接均超时。用户在阿里云安全组添加并保存入方向 TCP 8080 规则后，2026-10-11 已完成独立公网复测：首页、体验页、优惠券页、本地图片均 HTTP 200；健康状态 UP，分类、店铺列表/详情、缓存观察和功能开关接口成功；匿名本人资料请求 401，公开运维和 Prometheus 路径 404。网站现在可以通过公网地址访问。详细记录见 [云端部署验收](../docs/cloud-deployment-verification.json)。

以下是最小验收清单。迁移后的版本首次部署或升级后需要重新逐项检查，业务场景需要另行验证：

- 首页返回真实店铺；图片成功加载。
- 代理分类接口 success=true；Java与数据库未公开宿主端口。
- 邮箱验证码登录（验证码从 Java 日志取得，或接入 SMTP 后从邮箱取得）后 `/user/me` 返回当前用户；无登录读取本人订单返回 401；登出后旧 access token 返回 401。
- 签到与普通领券正常，重复领取按当前状态返回原订单或明确忙碌。
- 缓存观察有两次读取轨迹。
- 体验页的 Kafka 消费线程、业务消息（Kafka）、CDC 连接（Debezium）显示未启用，避免把开关关闭当故障；缓存采样表的缓存层只有请求内缓存、Caffeine、Redis、MySQL 四层。
- 专用管理员配置有效，普通访客不能执行管理操作。
- 外部电脑访问成功，内存与容器运行稳定，没有 OOM重启。

迁移前版本的服务器部署和公网入口曾通过上述验收并分享过体验链接。迁移后的版本需要重新完成清单后再分享。领券、秒杀、支付和管理员操作等业务场景仍需按对应章节另行验证；部署验收不代表所有业务回归通过。
