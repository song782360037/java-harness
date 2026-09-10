# 开发边界

## 模块

```text
harness-spi        → JDK：会话、执行、事件、引擎/模型/存储契约
harness-core       → spi：DefaultAgentEngine、ConversationContext
harness-adapters   → spi + Spring JDBC + Jackson + JDK HttpClient：模型适配、JdbcSessionStore
harness-server     → core + adapters + Spring Boot：装配、迁移、REST、SSE
harness-web        → Web API：Vue 工作台
```

core 不导入 adapters/server，不依赖 Spring、数据库或 HTTP。生产环境固定装配 adapters 中的 OpenAiChatCompletionProvider；厂商通过地址、Key、模型名配置切换。模拟器仅存在于 server 的 test 目录，由测试显式注入，不进入应用 JAR。ModelProvider 必须显式提供模型身份，并遵守取消和资源归属约定：stream 返回意味着本机的流消费已结束，不能在返回后继续回调；不代表远端推理或计费已经撤销。ModelException 只允许固定的用户可见消息，不能带上游正文或敏感异常原因。

目前 Run 对应一次模型流，ConversationContext 将历史投影为不可变 ModelRequest / ModelMessage。引擎先持久化快照，再交由适配器序列化为 Chat Completions 消息；不包含工具循环或自动重试。不要将其描述为完整 ReAct Agent。

## 配置

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `HARNESS_PORT` | 8080 | 本地后端端口 |
| `spring.datasource.url` | `jdbc:h2:file:./data/harness;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0` | 单实例文件数据库 |
| `HARNESS_RUN_TIMEOUT` / `harness.run-timeout` | 120s | 一次 Run 的执行时间预算 |
| `HARNESS_CONTEXT_MAX_CHARACTERS` / `harness.context.max-characters` | 32000 | 消息正文 UTF-16 字符预算，4000–256000，不是 token 限制 |
| `HARNESS_MODEL_API_KEY` | 空 | Bearer Key，只在后端配置 |
| `HARNESS_MODEL_API_KEY_REQUIRED` | true | 无鉴权服务可显式设 false；不影响模型名必填 |
| `HARNESS_MODEL_NAME` | 空 | 必须显式指定实际模型 ID，最长 128 字符 |
| `HARNESS_MODEL_BASE_URL` | https://api.openai.com/v1 | 基础地址，不含 /chat/completions |
| `harness.model.connect-timeout` | 10s | HTTP 连接超时 |
| `HARNESS_BACKEND_URL`（前端 .env.local） | http://127.0.0.1:8080 | Vite 开发代理 |

SSE 使用持久化事件轮询（200ms）和虚拟线程。最多 32 条并发订阅，每条发送最多 128 个事件的批次；约 55 秒主动结束，由 EventSource 携带 Last-Event-ID 重连。慢客户端拥有独立发送线程，不占用引擎锁；生产吞吐和恶意慢连接尚未压测。

前端按事件通知合并读取会话快照；`lastSeq` 去重和版本检查避免乱序 HTTP 响应覆盖新状态。快照读取与事件追加在同一存储实例中串行化，不存在读取快照后漏掉事件的窗口。当前列表与会话快照尚未分页，适合本地小规模使用。

## 构建和数据

使用 `npm ci` 与 Maven Wrapper。前端构建后执行 `mvnw.cmd -Pweb clean verify` 可合并打包；后端普通构建不需要 Node。切换模式使用 clean，避免静态产物残留。

Flyway 管理 SQL 版本。已发布迁移禁止就地修改，应增加新迁移。默认数据库位于进程工作目录的 data 下，禁止存放到 target（本地一次性验证数据库例外）。数据库文件已在 .gitignore 中排除。

V3 增加 harness_model_request 和 Run 的 context_summary。快照插入、摘要更新与 CONTEXT 事件同一事务提交；唯一 run_id 防止覆盖。旧记录保持 context=null，GET 快照返回 204。消息快照按需读取，普通会话快照只包含计数摘要，避免在每次 SSE 刷新时重复加载所有历史请求。

历史候选以当前 Run 的 QUEUED 事件为界，按 seq 顺序查询。每次组装读取该会话此前的 Run，依然适合本地小规模使用；发送内容受预算约束，数据库历史读取尚未分页。未来引入工具消息时需扩展完整轮次配对规则，不能直接套用当前 user/assistant 交替契约。

本阶段使用引擎级协调锁和单例存储锁，H2 文件由一个进程持有。不能将当前设计直接扩展为多 JVM 共享库，也不提供 exactly-once 外部副作用语义。

## 验证

`harness-server/src/test` 使用实际 H2、迁移和引擎验证状态与持久化；HTTP 集成测试启动随机端口的 Spring Boot 服务并检查 SSE。测试数据库隔离，不使用 data 下的用户数据库。

OpenAiChatCompletionTest 使用随机端口的本地 HTTP 服务验证通用适配器的最小请求字段、路径前缀、Bearer / 无鉴权、任意模型名、断流、取消传播、无自动重试以及旧数据保留。使用占位 Key，不访问公网模型。真实账户联调未执行。Shell、文件编辑、鉴权审批、插件及自动压缩仍未实现。
