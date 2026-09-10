# Java Harness

Java 原生 Agent 运行时与本地 Web 编程工作台。启动后直接使用 OpenAI Chat Completions 兼容接口，支持多轮对话、流式回答和请求消息快照。

## 当前范围

已实现会话创建与切换、单会话单活动任务、兼容接口流式输出、取消、超时和失败状态、模型配置记录、H2 文件持久化、Flyway 迁移、SSE 事件补发及刷新恢复。

模型使用统一的地址、Key、模型名配置，可连接 OpenAI、DeepSeek 或实现相同协议的网关及本地模型服务。同一会话自动携带已完成的真实问答；尚未实现工具执行、工作区文件操作、审批、插件加载或自动续跑。模拟运行模式已移除，配置缺失时会明确提示，不生成模拟回答。

## 工程结构

```text
java-harness/
  harness-spi/          Session / Run / Event 与引擎、模型、存储契约
  harness-core/         执行调度、上下文组装与预算、状态仲裁、取消与截止时间
  harness-adapters/     OpenAI Chat Completions HTTP 适配与 JDBC 存储
  harness-server/       Spring Boot 装配与 Web 入口，依赖 core、adapters
  harness-web/          Vue 3 + TypeScript + Vite
  .mvn/wrapper/         固定 Maven 版本的 Wrapper
  .github/workflows/    Windows / Linux 构建工作流
  docs/                设计与开发说明
```

core/spi 保持普通 Java 依赖；adapters 使用 JDK HttpClient、Jackson 和 Spring JDBC；server 负责 Spring Boot、Flyway、REST/SSE 和应用装配。

## 环境

- JDK 21，设置 `JAVA_HOME`。
- Node.js 22.12+，推荐 Node.js 24 LTS；npm。
- Maven Wrapper 固定 Maven 3.9.16，无需单独安装 Maven。首次构建需要联网下载。

项目使用 Spring Boot 3.5.16。前端依赖版本固定，并提交 `package-lock.json`。

Windows 当前终端配置示例，请替换为自己的 JDK 路径：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## 后端构建与启动

在项目根目录执行：

```powershell
.\mvnw.cmd -B -ntp clean verify
java -jar harness-server\target\harness-server-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:8080`。检查 [应用存活状态](http://127.0.0.1:8080/actuator/health)，预期返回 `{"status":"UP"}`。

仅构建后端时不包含首页，访问 `/` 返回 404 是预期行为。

IDEA 导入根目录 `pom.xml`，选择 JDK 21，运行 `HarnessApplication` 即可。使用命令行开发模式时，先安装模块依赖：

```powershell
.\mvnw.cmd -B -ntp install
.\mvnw.cmd -pl harness-server spring-boot:run
```

Linux/macOS 使用 `sh ./mvnw` 替代 `.\mvnw.cmd`，路径使用 `/`。

## 前端开发

另开一个终端，在项目根目录执行：

```powershell
npm --prefix harness-web ci
npm --prefix harness-web run dev
```

访问 [前端开发页面](http://127.0.0.1:5173)。开发代理将 `/api` 与 `/actuator/health` 转发到后端，不需要为本地开发开放 CORS。

更换后端端口时设置 `HARNESS_PORT`；前端在 `harness-web/.env.local` 中设置对应 `HARNESS_BACKEND_URL`，示例见 `.env.example`。前端端口固定为 5173，占用时会明确报错。

## 打包为一个可运行 JAR

```powershell
npm --prefix harness-web ci
npm --prefix harness-web run build
.\mvnw.cmd -B -ntp -Pweb clean verify
java -jar harness-server\target\harness-server-0.1.0-SNAPSHOT.jar
```

此时访问 [Java Harness](http://127.0.0.1:8080/) 使用工作台。前端资源位于 JAR 的 `BOOT-INF/classes/static`，最终运行只需要 JDK，不需要 Node.js。

`web` profile 不执行 npm，只复制已经构建的 `harness-web/dist`；缺少 `dist/index.html` 时构建失败并给出提示。修改前端后需重新执行前端构建。切换打包模式时使用 `clean`，避免旧静态资源残留。

## 检查

```powershell
npm --prefix harness-web run typecheck
npm --prefix harness-web run build
.\mvnw.cmd -B -ntp clean verify
```

自动化测试覆盖状态、并发、取消、超时、事务、文件库恢复和实际 HTTP/SSE 接口。GitHub Actions 预置 Windows/Linux 构建，实际 CI 结果以远程运行记录为准。

## 使用与数据

1. 按下方说明配置模型并重启 Java 服务，页面显示模型名及“已配置”。
2. 点击“新建会话”，输入文本并发送，观察模型输出；需要时点击停止。
3. 刷新页面保留会话与已保存内容；关闭浏览器不会取消任务。

### 多轮对话与模型输入

在同一会话继续追问，模型会收到此前已完成且有回答的真实问答，再加上本次输入。失败、取消、超时、中断、模拟和空回答记录会被跳过。历史按会话提交事件序号排列，不依赖系统时间。新建会话开始独立对话。

默认上下文上限为 **32000 个 UTF-16 字符**（所有消息正文合计），可通过 `HARNESS_CONTEXT_MAX_CHARACTERS` 设置为 4000–256000。超限时只保留最近的完整问答后缀，移除更早轮次；若最近一轮本身放不下，将只发送本次输入。页面显示带入、裁剪和跳过的历史数量。该限制不是 token 计数，也不保证符合所有模型的上下文窗口；自动摘要尚未实现。

每条新回答可展开“查看本次模型输入”，检查调用前保存的消息快照。快照保存失败时不调用模型；存在快照并不代表上游成功接收。幂等重试不会重新组装或覆盖快照。旧记录没有快照时不伪造补写。

同一会话在切换模型或服务后，合格的历史问答会继续发送给当前配置的服务；希望隔离历史时请新建会话。刷新或重启后继续从持久化记录组装，不会重新执行旧任务。

数据默认写入**进程工作目录**下的 `data/harness.mv.db`，启动时自动执行迁移。IDEA 和命令行请使用同一个工作目录以访问同一份数据。不要同时启动两个实例操作这份数据库。

正常退出会请求取消活动任务；异常退出后的活动任务在下次启动时标记为 `INTERRUPTED`，保留已提交输出，不自动重试。终态任务不再接受输出。执行超时可以通过 `HARNESS_RUN_TIMEOUT` 或 `--harness.run-timeout=30s` 调整。

## 接入 OpenAI Chat Completions 兼容服务

在启动 Java 服务的终端中设置环境变量；IDEA 则在运行配置的环境变量中设置：

```powershell
$env:HARNESS_MODEL_BASE_URL = 'https://api.openai.com/v1'
$env:HARNESS_MODEL_API_KEY = '<服务提供方的 API Key>'
$env:HARNESS_MODEL_NAME = '<该服务实际提供的模型 ID>'
java -jar harness-server\target\harness-server-0.1.0-SNAPSHOT.jar
```

替换 Key 和模型 ID 占位符；不要将 Key 写入仓库或前端配置。模型名没有厂商默认值，必须显式填写，支持 `组织/模型:标签` 等格式。修改配置后重启 Java 服务。页面显示“已配置”仅表示本地必填项齐全，不表示鉴权或网络已验证。

基础地址默认 `https://api.openai.com/v1`。程序在末尾追加 `/chat/completions`，保留地址中的 `/v1` 或网关路径前缀；不要将完整的 `/chat/completions` 地址填作基础地址。远程地址要求 HTTPS；HTTP 仅允许回环地址。不自动跟随重定向或重试。

无需设置运行模式。切换服务时修改以下配置并重启：

| 服务 | `HARNESS_MODEL_BASE_URL` 示例 | 模型名 / Key |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | 该账户可用的 Chat Completions 模型 ID 和 Key |
| DeepSeek | `https://api.deepseek.com` | 该服务可用的模型 ID 和 Key |
| 兼容网关 | `https://你的网关/v1` | 网关配置的模型 ID 和 Key，路径以网关为准 |
| 本地兼容服务 | `http://127.0.0.1:11434/v1` | 本机实际提供的模型 ID；端口与路径以本机服务为准 |

对于明确不需要鉴权的本地服务，可额外设置 `$env:HARNESS_MODEL_API_KEY_REQUIRED = 'false'` 并清空 `HARNESS_MODEL_API_KEY`。Key 为空时不发送 Authorization；Key 非空时发送 `Authorization: Bearer ...`。默认仍要求 Key，模型名称始终必填。

请求体只发送标准的 `model`、`messages`、`stream`，不附加 `thinking`、固定 token 上限等可选参数。生成长度及思考模式使用上游默认值；本地仍执行 120 秒总超时与 100000 字符输出上限。只保存 `delta.content` 回答文本；使用标准流式 `choices` / `delta` / `finish_reason` / `[DONE]` 的文本服务可接入。只支持 Responses API、非流式 JSON、自定义鉴权头或不同流协议的服务不在当前兼容范围内。没有承诺所有标称兼容的服务和模型均已实测。

从上一版配置迁移：删除 `HARNESS_MODEL_PROVIDER` 和模拟延迟配置，这些设置已不再读取。更早的 `DEEPSEEK_BASE_URL` / `DEEPSEEK_API_KEY` / `DEEPSEEK_MODEL` 分别改为 `HARNESS_MODEL_BASE_URL` / `HARNESS_MODEL_API_KEY` / `HARNESS_MODEL_NAME`。旧厂商环境变量不再读取；启动自动执行 V3 迁移，保留旧记录，新增上下文摘要与消息快照存储。

停止会取消本机 HTTP 请求；无法保证远端已经生成的内容不计费。断流、长度截断和服务错误会保留已接收文本并标记失败，不伪装成正常完成。当前已通过本地 HTTP 协议测试，尚未进行真实账户联调。

新记录的 provider 表示适配协议（openai-compatible），model 保存配置的模型名；不记录 API Key 或服务地址。已有模拟及 DeepSeek 记录保留原始标识和输出，不重新执行；V2 仍只负责为 V1 旧模拟记录补写模型信息。测试用模拟器仅位于 test 源码目录，不会打包进应用。

API、状态和事件约定见 [最小运行闭环](docs/runtime.md)。此版本用于本机单用户体验，默认绑定回环地址并检查 API Host/Origin；尚未提供登录、多用户隔离或远程部署能力。

参阅 [开发边界](docs/development.md)、[本次验证记录](docs/verification.md) 和 [架构设计稿](docs/architecture-proposal.md)。设计稿包含后续规划，不代表已经实现。
