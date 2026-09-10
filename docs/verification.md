# 验证记录

日期：2026-09-10。

## 当前阶段：多轮上下文与消息快照

- 前端 `npm --prefix harness-web run build`：TypeScript 检查及 Vite 构建通过。
- 最终 Maven `-Pweb verify`：53 项测试，0 失败、0 错误、0 跳过，合并 JAR 构建通过。
- ConversationContextTest 新增 9 项：完整多轮及幂等重试、跨会话隔离、排除模拟/失败/取消/超时/中断/空回答、模型切换后的合格历史、完整轮次裁剪与过大最近轮次、事件序号排序、快照事务回滚且不调用模型、排队取消无快照、文件库重开恢复、V2 迁移保留旧数据、精确字符边界与配置校验（部分内容合并在同一用例）。
- HTTP 协议测试新增 1 项：第二轮发送的 messages 与数据库快照逐项一致，并包含第一轮问答。API 测试新增 1 项：快照 200、尚无快照 204、无 Run 404 及无 Key 字段。
- 首次完整验证中，原并发幂等测试在 future.get 的 3 秒等待处超时；将该用例的调度等待放宽为 10 秒、任务预算设为 30 秒，保留一次创建、幂等身份及忙碌拒绝断言。随后完整 53 项验证通过。其余超时行为测试继续保留。
- 浏览器实测：第一轮输入、第二轮追问、携带历史计数、展开 user/assistant/user 请求快照、刷新恢复、超出 4000 字符测试预算后的明确裁剪提示。已做桌面截图检查。
- 页面验证使用随机端口、`jdbc:h2:mem:context-ui-smoke` 和本地 HTTP 协议桩，测试库与用户 data 隔离；临时服务与页面已关闭。
- V3 自动新增快照表和 Run 摘要，不修改 V1/V2，不伪造旧请求快照。当前预算为 UTF-16 正文字符数，不是 token 限额。公网模型、多厂商实际窗口、Linux、远程 CI 和前端自动化测试未验证。

## 上一阶段：仅使用真实模型接口

- 前端 TypeScript 检查和 Vite 构建通过；Maven `-Pweb clean verify` 通过：42 项测试，0 失败、0 错误、0 跳过，合并 JAR 构建成功。
- 新增 RealModelConfigurationTest 两项，启动实际 Spring Boot 上下文验证：旧 simulated 设置不会启用模拟器；配置缺失时拒绝创建 Run，不生成假回答。
- 模拟器和函数式测试替身已移到 test 目录，API 测试通过测试配置显式注入。检查应用 JAR 及内嵌 adapters JAR，确认不存在模拟器及测试替身类。
- 页面已移除模拟模式和场景选择，保留旧会话的来源标签；本轮完成前端编译检查，未重复浏览器验证。
- 所有测试使用隔离数据库；未修改默认 data 数据库，未重启用户正在运行的服务，未进行公网模型联调。使用新 JAR 或重启 IDEA 中的应用后生效。

## 上一阶段：OpenAI Chat Completions 通用适配

- 前端 `npm --prefix harness-web run build`：TypeScript 检查和 Vite 构建通过。
- 后端 `mvnw.cmd -B -ntp -Pweb clean verify`：40 项测试，0 失败、0 错误、0 跳过；合并 JAR 构建通过。clean 清除了重命名前的类和测试产物。
- OpenAiChatCompletionTest 共 29 项：保留原流式、错误、取消、超时、数据迁移验证；新增基础地址及网关路径前缀、模型 ID 标签、无 Key / 无 Authorization 模式、模型名必填、refusal 和旧 DeepSeek 记录幂等重试。请求体断言只允许 model / messages / stream，确保没有残留厂商参数。
- 原运行测试 8 项和 API 测试 3 项继续通过。
- 实际 JAR 使用 HARNESS_MODEL_* 环境变量启动，配置带 `/gateway/v1/` 前缀的本地协议桩、`local/example:8b` 模型名和无 Key 模式。浏览器创建会话、发送输入并收到完成回答，界面模型标签与截图检查正常。
- 验证采用随机端口和 `jdbc:h2:mem:chat-compatible-smoke`，未访问用户 data 数据库；临时服务和页面已关闭。
- 本轮 OpenAI 官方文档页面请求返回 403，未能实时核对文档。没有进行 OpenAI、DeepSeek 或其他公网账户联调；本地协议验证不代表所有兼容服务及模型均已实测。未运行远程 CI、Linux 或前端自动化测试。

## 前一实现：DeepSeek 单轮流式适配（历史验证记录）

- 前端 TypeScript 检查与 Vite 构建通过。
- Maven `-Pweb verify` 通过：34 项测试，0 失败、0 错误、0 跳过，合并 JAR 打包成功。
- 新增 DeepSeek 协议验证 23 项（含参数化场景）：随机端口本地 HTTP 服务、真实 HttpClient 适配器和 H2 存储，覆盖 UTF-8 字节分片、CRLF / 多行 SSE、usage、正常结束、意外 EOF、缺失结束原因、无效 JSON、上游错误、超长事件、错误 Content-Type、截断 / 过滤 / 工具调用 / 资源不足、401 / 402 / 429 / 500 / 302、等待响应头及响应体时取消、响应体超时、Unicode 大块分段、Key / 场景校验、V1 迁移保留旧数据。
- 原有运行测试 8 项及 HTTP/SSE 测试 2 项继续通过；另新增模型配置摘要 API 测试 1 项。
- 浏览器实际操作：DeepSeek 模式隐藏模拟场景、显示配置状态与模型名；创建会话、提交、经真实适配器接收本地协议桩回答、显示完成状态、刷新后恢复输出和模型来源。桌面页面已做截图检查。
- 浏览器验证使用随机端口和 `jdbc:h2:mem:deepseek-ui-smoke`，未连接默认 data 用户数据库。Key 为测试占位符，上游为本机 HTTP 桩。
- 最后调整发送按钮靠右的 CSS 后，重新通过前端检查和构建，以 `-Pweb -DskipTests package` 更新 JAR；Java 源码未再改变，34 项测试结果来自此前 verify。
- 临时验证服务已停止。未进行真实 DeepSeek 账户联调、前端自动化测试、Linux 运行或远程 CI 验证。上述协议桩结果不能代替公网鉴权、可用性和实际模型回答质量的验证。

## 上一阶段：最小运行闭环（2026-09-09）

- 前端 `npm run build`：TypeScript 检查与 Vite 生产构建通过。
- 后端 Maven `-Pweb verify`：10 项测试，0 失败、0 错误、0 跳过；可运行 JAR 打包通过。
- 运行测试 8 项：正常完成和 Unicode 事件投影、并发幂等及忙碌拒绝、取消等待收尾并丢弃迟到输出、排队取消不调用模型、超时/失败区分、事件插入失败后的事务回滚、文件库重开及活动状态恢复、非法输入/终态不可复活。
- HTTP 集成测试 2 项：实际随机端口服务上的提交/重试/取消/Origin 检查，以及 SSE 历史读取、Last-Event-ID 补发、断开不取消和快照读取。
- 浏览器实际操作：创建会话、提交、输出完成、刷新恢复、停止任务正常。
- 实际强制终止 JVM 并重启：活动 Run 恢复为 INTERRUPTED，已提交文本逐字保留，Run 数量不变，浏览器自动重连后显示中断原因。
- 实际正常关闭 JVM：同时存在活动 Run 和 SSE 订阅时，服务正常优雅退出；重新打开临时文件库确认任务终态为 CANCELLED。已调整关闭顺序，先结束 SSE 和引擎，再停止 Web 服务。
- 页面已做桌面截图检查。前端自动化测试、移动端视觉验证、Linux 运行及远程 CI 未执行。

本阶段仍使用模拟模型。以上结果不能作为真实模型、文件操作、工具执行、审批或生产吞吐的验证。

运行验证使用 `harness-server/target/ui-smoke` 临时文件库与 18080 端口，没有写入默认 data 用户数据库。正式默认超时为 15 秒；浏览器崩溃恢复验证临时设为 120 秒。

最终仅修正前端重连成功后的过期错误提示，重新通过前端类型检查和构建，再以 `-Pweb -DskipTests package` 更新合并 JAR；该次打包没有重跑测试，后端 10 项测试结果来自此前相同 Java 源码的 verify。临时验证服务已停止。

## 初始阶段：工程骨架（历史记录）

本地环境：Windows PowerShell、Amazon Corretto 21.0.10、Maven Wrapper 3.9.16、Node.js 23.11.0、npm 10.9.2。项目推荐使用 Node.js 24 LTS；本次实际使用的是机器已有且满足引擎要求的 Node.js 23。

| 检查 | 结果 |
| --- | --- |
| `mvnw.cmd -B -ntp clean verify` | 父工程及四个后端模块构建通过 |
| `npm ci --registry=https://registry.npmjs.org` | 锁文件安装通过 |
| `npm run build` | Vue TypeScript 检查与 Vite 生产构建通过 |
| `mvnw.cmd -B -ntp -Pweb clean verify` | 前端资源合并进可运行 JAR，构建通过 |
| 纯后端 JAR | `/actuator/health` 返回 UP；`/` 返回预期的 404 |
| 合并 JAR | 首页、JS、CSS 均返回 200；health 返回 UP |
| 浏览器打开合并 JAR 首页 | 正常渲染“本地编程工作台”占位页 |
| Vite 开发服务 | 首页返回 200；健康检查代理转发后返回 UP |
| 构建配置检查 | POM XML 有效；Wrapper/锁文件未包含本机目录和镜像地址 |
| Java 源码范围检查 | 一个 Spring Boot 启动类，三个 package-info；没有业务类 |

集成检查使用临时端口 18080、15173，验证后已停止服务。默认端口仍是 8080、5173。

合并打包时修正了文件检查规则对含 `..` 路径的判定问题，改为使用 Maven 多模块根目录定位前端产物。

当前没有业务测试用例，Maven 输出 `No tests to run`；以上结果不代表任何 Agent 或业务能力经过测试。Windows/Linux GitHub Actions 仅已添加配置，尚未在远程运行。Linux 启动未在本机验证。
