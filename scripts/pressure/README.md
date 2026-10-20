# 高并发真实 HTTP 验收

依赖：JDK 17+、Node 20+、已启动的项目 MySQL/Redis/Kafka，以及 `mvn package` 生成的 Spring Boot jar。工具源码不包含依赖 jar。`prepare.ps1` 自动从 `target/*.jar` 的 `BOOT-INF/lib` 精确提取 MySQL、SnakeYAML、Jackson 三个库、Kafka clients、SLF4J 共七个依赖并编译 Java 辅助程序到 `target/pressure-tools`，不下载依赖、不读取硬编码 classpath。

连接信息从当前项目 `application.yaml` 和对应环境变量在内存读取，口令不输出。数据库名称默认校验为 `shanhai_ticket`；使用其他独立测试库时显式设置 `HMDP_FIXTURE_DATABASE`。仅允许本机 HTTP 地址；临时登录 token 只保存在本地清单，清理时失效。应在隔离的开发或验收环境执行，确保没有其他业务写入。

```powershell
# 项目根目录；可通过 -JdkPath 指定 JDK，或事先配置 JAVA_HOME。
$fixture=Join-Path $PWD 'target\pressure-fixtures'
& .\scripts\pressure\run-fixture.ps1 -Action install-schema -FixtureDirectory $fixture
& .\scripts\pressure\run-fixture.ps1 -Action setup -FixtureDirectory $fixture -Extra @('4000','64')
```

先保存原业务数据及表结构快照，再创建 16 张状态表。setup 建立 4000 个临时用户、17 篇临时笔记、64 张临时券，每券库存 4000，并记录精确 ID。输出独立秒杀 stream、Kafka topic/group。启动应用高并发配置时，将 `hmdp.seckill.outbox-stream`、`hmdp.seckill.topic`、`spring.kafka.consumer.group-id` 覆盖为这些独立名称。Redis 应配置 noeviction 和持久化，Kafka 必须实际可用。

```powershell
node .\scripts\pressure\http-suite.js --fixture $fixture --project $PWD --base http://127.0.0.1:8081/api --clients 3500 --rate 3500 --seconds 30
```

先执行独立预热，再按顺序测点赞热点、点赞分散与秒杀。整个 suite 共用一个 Node 进程和同一个 HTTP keep-alive Agent，避免每阶段新建数千连接导致本机临时端口耗尽。每次突发前发送 3500 次静态资源 GET，并确认空闲连接池已有 3500 个可复用连接；预建连接独立记录，完全排除业务成功数和吞吐量。每类分别测 3500 个独立登录用户的同时请求，以及目标 3500 次/秒持续 30 秒。每个逻辑用户最多一个未完成请求；点赞成功后才切换目标状态，秒杀每成功一轮更换一张券，避免重复点赞或重复购买被计入有效吞吐。`--kind likes` 或 `--kind seckill` 可单独测一类，`--warmup false` 可明确跳过预热；`--jdk 完整JDK目录` 可传给辅助编译工具。

每阶段报告：计划发送、实际发送、成功有效变更、拒绝、网络/业务失败、超时、无空闲客户端导致的未发送数，以及延迟 P50/P95/P99、超过前端两秒的数量、已连接且未完成请求及已写出且未完成请求的峰值。精确注入窗口内的 TPS 不计窗口结束后的晚完成请求，同时另报包括请求排空的 TPS。

每阶段都会检查 Redis outbox 已排空、MySQL 逐用户状态与关系、历史基数和计数、Redis 的计数及真实版本增量。秒杀额外核对订单号、一人一单、库存不超卖，以及 Kafka 各分区已提交 offset 追平末尾。HTTP 接受速度和最终持久化是两个指标，不把异步接受当成同步提交。

```powershell
& .\scripts\pressure\run-fixture.ps1 -Action kafka-lag -FixtureDirectory $fixture
& .\scripts\pressure\run-fixture.ps1 -Action audit -FixtureDirectory $fixture -Extra @('完整报告路径.json','180')
# 停止产生测试请求后，清理精确登记的临时记录与登录态。
& .\scripts\pressure\run-fixture.ps1 -Action cleanup -FixtureDirectory $fixture
```

未排空 Redis/Kafka 时拒绝 reset/cleanup。清理核对原用户数、原券库存、原订单数，以及六张业务表完整原始行。只删除自己的临时数据，不清空业务库、共享 stream 或公共限流 key。

单机测试会受客户端和所有服务竞争 CPU/内存影响。3500 并发连接与每秒 3500 次有效操作分开判定；拒绝、未知结果、空操作、只连不发请求都不能算达标。直连 8081 与经过 nginx 的整链路性能也应分别报告。
