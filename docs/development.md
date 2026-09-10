# 开发边界

## 模块

```text
harness-spi        → JDK：会话、执行、事件、引擎/模型/存储契约
harness-core       → spi：DefaultAgentEngine
harness-adapters   → spi + Spring JDBC：模拟模型、JdbcSessionStore
harness-server     → core + adapters + Spring Boot：装配、迁移、REST、SSE
harness-web        → Web API：Vue 工作台
```

core 不导入 adapters/server，不依赖 Spring、数据库或 HTTP。模拟模型只存在于 adapters。未来真实模型实现 ModelProvider 时必须遵守取消和资源归属约定：stream 返回意味着它拥有的工作已结束，不能在返回后继续回调。

目前 Run 对应一次模型流，不包含工具循环、历史上下文投影或重试。不要将其描述为完整 ReAct Agent。新增能力前先扩展明确的契约和测试。

## 配置

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `HARNESS_PORT` | 8080 | 本地后端端口 |
| `spring.datasource.url` | `jdbc:h2:file:./data/harness;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0` | 单实例文件数据库 |
| `harness.run-timeout` | 15s | 一次 Run 的执行时间预算 |
| `harness.simulated-chunk-delay` | 100 | 每个模拟分段的延迟毫秒数，允许 0–1000 |
| `HARNESS_BACKEND_URL`（前端 .env.local） | http://127.0.0.1:8080 | Vite 开发代理 |

SSE 使用持久化事件轮询（200ms）和虚拟线程。最多 32 条并发订阅，每条发送最多 128 个事件的批次；约 55 秒主动结束，由 EventSource 携带 Last-Event-ID 重连。慢客户端拥有独立发送线程，不占用引擎锁；生产吞吐和恶意慢连接尚未压测。

前端按事件通知合并读取会话快照；`lastSeq` 去重和版本检查避免乱序 HTTP 响应覆盖新状态。快照读取与事件追加在同一存储实例中串行化，不存在读取快照后漏掉事件的窗口。当前列表与会话快照尚未分页，适合本地小规模使用。

## 构建和数据

使用 `npm ci` 与 Maven Wrapper。前端构建后执行 `mvnw.cmd -Pweb clean verify` 可合并打包；后端普通构建不需要 Node。切换模式使用 clean，避免静态产物残留。

Flyway 管理 SQL 版本。已发布迁移禁止就地修改，应增加新迁移。默认数据库位于进程工作目录的 data 下，禁止存放到 target（本地一次性验证数据库例外）。数据库文件已在 .gitignore 中排除。

本阶段使用引擎级协调锁和单例存储锁，H2 文件由一个进程持有。不能将当前设计直接扩展为多 JVM 共享库，也不提供 exactly-once 外部副作用语义。

## 验证

`harness-server/src/test` 使用实际 H2、迁移和引擎验证状态与持久化；HTTP 集成测试启动随机端口的 Spring Boot 服务并检查 SSE。测试数据库隔离，不使用 data 下的用户数据库。

目前没有真实模型、Shell、文件编辑、鉴权审批、插件或自动压缩测试，因为这些功能尚未实现。后续接入模型 HTTP 请求时，必须单独验证断流、取消传播与请求重试语义。
