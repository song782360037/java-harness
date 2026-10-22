# 开发边界

前端 `SettingsPage.vue` 统一管理提供商、模型和工作区，`WorkspaceSettings.vue` 复用于页内面板和新建／编辑弹窗；`ComposerSelectors.vue` 负责输入区的工作区、分组模型和思考强度菜单。进入设置仅隐藏会话界面，保留草稿、快照与 SSE。

工作区保存在 `harness_workspace`，会话通过 `workspace_id` 复用自定义名称、目录列表、图标与颜色。`/api/workspaces` 创建和查询工作区，`/api/workspaces/{id}` 以 revision 校验编辑；会话创建／绑定接口接受 `workspaceId`，仍兼容旧 `workspace` 路径。旧会话按相同目录关联工作区，历史路径和 Run 快照保留原值。多目录快照由 `WorkspaceRoots` 编码为 JSON 数组，单目录继续保存路径；`MultiWorkspaceTools` 要求每次调用用 `root` 指定目录编号，并把读写、Git 和命令调用路由到原有单目录工具。审核路径用 `@1/`、`@2/` 标明目录，命令卡片显示实际执行路径。编辑目录列表会原子更新所有绑定会话；共享工作区有活动任务或目录快照存在待审核修改时拒绝目录变更。索引不在本次范围内。

`UiIcon.vue` 统一线性图标；`MarkdownContent.vue` 通过禁用原始 HTML 的 markdown-it 渲染模型正文，不直接信任模型生成的 HTML。会话导航按工作区分组并在客户端过滤搜索；输入区的示例仅填入草稿。`npm --prefix harness-web test` 覆盖主题状态与 Markdown 安全边界。

## 模块

全局颜色由 `styles/theme.css` 定义，布局和组件样式使用语义变量；只有外观选择卡片的缩略预览固定展示明暗示例。`public/theme.js` 在主样式和应用模块前执行，恢复 `harness.theme` 偏好并监听系统及跨标签页变化；`theme.ts` 向 Vue 提供响应式状态，`AppearanceSettings.vue` 提供选择入口。偏好属于浏览器站点，不写入模型数据库。`npm --prefix harness-web run test:theme` 使用 Node 内置测试工具验证主题状态与存储边界。

```text
harness-spi        → JDK：会话、执行、事件、引擎/模型/存储契约
harness-core       → spi：DefaultAgentEngine、ConversationContext
harness-adapters   → spi + Spring JDBC + Jackson + JDK HttpClient：模型适配、JdbcSessionStore、LocalWorkspaceTools
harness-server     → core + adapters + Spring Boot：装配、迁移、REST、SSE
harness-web        → Web API：Vue 工作台
```

core 不导入 adapters/server，不依赖 Spring、数据库或 HTTP。SettingsService 实现 SPI 的 RunConfigurationSource，提交时捕获不可变配置；新任务执行时按捕获的配置创建 OpenAiChatCompletionProvider 和会话专属 LocalWorkspaceTools，结束时关闭该任务的 HTTP 客户端，幂等重试不创建资源。固定 provider 构造器供测试或嵌入场景使用，资源仍由调用者管理。厂商通过页面地址、Key、模型名切换。模拟器仅存在于 server 的 test 目录，不进入应用 JAR。ModelProvider 必须显式提供模型身份，并遵守取消和资源归属约定：stream 返回意味着本机的流消费已结束，不能在返回后继续回调；不代表远端推理或计费已经撤销。ModelException 只允许固定的用户可见消息，不能带上游正文或敏感异常原因。

目前 Run 可包含多次模型请求与工作区工具调用。ConversationContext 按完整轮次保留 user/assistant/tool 消息，验证调用与结果配对；引擎每次请求前先持久化 ModelRequest，再由适配器序列化为标准 Chat Completions 消息。ModelProvider.complete 返回本次响应完整文本和调用列表；默认实现兼容旧的纯文本 stream。WorkspaceTools 增加携带 runId 的默认执行入口，core 只依赖契约。ReviewWorkspaceTools 包装只读工具并增加文件修改提案能力，真正写文件仅在独立审核 API 中执行。CommandWorkspaceTools 在外层增加等待审批的命令调用；没有自动重试。GitWorkspaceTools 在最内层的 LocalWorkspaceTools 之上增加只读 git_status / git_diff，构造时用 `git rev-parse --show-toplevel` 探测，仅当工作区根等于仓库根时暴露工具（子目录会让 git 报告工作区之外的路径）；调用使用直接 argv、`--literal-pathspecs`（拒绝 glob 展开）、`--no-textconv` 与 `--no-ext-diff`（.gitattributes 或配置不能借 textconv、外部驱动执行命令）、`core.fsmonitor=false`、`GIT_OPTIONAL_LOCKS=0`，清除 GIT_DIR/GIT_WORK_TREE/GIT_INDEX_FILE 等继承变量；`git_diff` 拒绝目录 pathspec（git 会展开目录并暴露其中的排除文件），并用 numstat 预检路径集合。WorkspacePaths 与 BoundedOutput 被文件工具和 git 工具共用，避免路径与截断规则漂移；解析输出使用独立的较大上限，统计不因显示截断失真。

## 文件审核

`FileChangeService` 在单例 SessionStore 的协调锁内校验任务/工作区、保存 V8 提案和审核结果；提案内容不可编辑，审批只能引用固定 ID。数据库变更与 FILE_CHANGE 事件同事务提交，页面据此重新读取卡片。应用按 PENDING → APPLYING（先提交意图）→ APPLIED / CONFLICT / UNCERTAIN 处理，拒绝为 PENDING → REJECTED；启动将遗留 APPLYING 标记为 UNCERTAIN，不猜测、不重放外部副作用。SessionStore 的锁序与引擎一致，文件服务持锁期间不反调引擎取消检查，避免锁反转。

`WorkspaceFileEditor` 限定相对路径、UTF-8 与文件大小，拒绝链接、排除目录、Windows 路径别名及不存在的父目录。比对完整原文件后才发布同目录临时文件；既有文件原子替换、新文件原子创建硬链接，保留 POSIX 权限/Windows ACL。不提供跨文件事务，也不保证任意外部进程与本服务之间的原子 CAS；此限制与磁盘/数据库提交分离必须在验收中明确。

`FileChanges.vue` 负责独立获取及处理审核记录，`FileChangeCard.vue` 在展开时计算差异；`fileDiff.ts` 使用固定版本 diff，设置计算时间及展示行数边界，超出时改用完整原文/结果供审核。所有代码以 Vue 文本插值展示，不执行 HTML。

## 配置

提供商连接信息保存在 `harness_model_provider`，模型及运行参数保存在 `harness_model_profile`，以 provider_id 外键关联。提供商独立保存名称、基础地址、加密 Key、鉴权要求、连接超时和 revision；模型保存名称、模型 ID、默认思考强度、运行参数和 revision，不重复持久化连接信息。下表的模型/运行环境变量仅在默认模型缺失时用于初始化；之后数据库值优先。端口、数据库位置、密钥文件和 Vite 代理仍属于启动配置。会话绑定独立工作区，`HARNESS_WORKSPACE_ROOT` 已停止读取。

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `HARNESS_PORT` | 8080 | 本地后端端口 |
| `spring.datasource.url` | `jdbc:h2:file:./data/harness;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0` | 单实例文件数据库 |
| `HARNESS_RUN_TIMEOUT` / `harness.run-timeout` | 120s | 一次 Run 的执行时间预算 |
| `HARNESS_CONTEXT_WINDOW_TOKENS` / `harness.context.window-tokens` | 16000 | 模型上下文窗口估算，1024–2000000 tokens |
| `HARNESS_MAX_OUTPUT_TOKENS` / `harness.context.max-output-tokens` | 4096 | 输出预留，64–131072 tokens 且小于窗口；用于输入预算，不是上游硬限制 |
| `HARNESS_SETTINGS_KEY_FILE` | `./data/settings.key` | API Key 加密所需的安装密钥，备份时与数据库一起保留 |
| `HARNESS_MAX_TOOLS_PER_REQUEST` / `harness.tools.max-tools-per-request` | 32 | 单次模型响应实际执行的工具上限，1–256；解析安全上限固定为 256 |
| `HARNESS_TOOL_TIMEOUT` | 5s | 单工具协作式截止时间 |
| `HARNESS_MODEL_API_KEY` | 空 | 首次初始化的 Bearer Key；页面支持保留、替换和清除 |
| `HARNESS_MODEL_API_KEY_REQUIRED` | true | 无鉴权服务可显式设 false；不影响模型名必填 |
| `HARNESS_MODEL_NAME` | 空 | 必须显式指定实际模型 ID，最长 128 字符 |
| `HARNESS_MODEL_BASE_URL` | https://api.openai.com/v1 | 基础地址，不含 /chat/completions |
| `harness.model.connect-timeout` | 10s | HTTP 连接超时 |
| `HARNESS_BACKEND_URL`（前端 .env.local） | http://127.0.0.1:8080 | Vite 开发代理 |

配置保存先校验再按 revision 乐观更新，冲突返回 409。查询/保存响应不包含 Key 明文或密文。LocalSecretCipher 使用 AES-256-GCM，每次加密生成随机 nonce；安装密钥存本机独立文件（POSIX 上创建为 0600，Windows 继承目录 ACL）。Key 为空时不生成密钥文件；解密失败时不重建、不静默清空。配置和安装密钥的后端对象不得写入日志，备份不能仅复制 H2 文件。

SSE 使用持久化事件轮询（200ms）和虚拟线程。最多 32 条并发订阅，每条发送最多 128 个事件的批次；约 55 秒主动结束，由 EventSource 携带 Last-Event-ID 重连。慢客户端拥有独立发送线程，不占用引擎锁；生产吞吐和恶意慢连接尚未压测。

前端按事件通知合并读取会话快照；`lastSeq` 去重和版本检查避免乱序 HTTP 响应覆盖新状态。快照读取与事件追加在同一存储实例中串行化，不存在读取快照后漏掉事件的窗口。当前列表与会话快照尚未分页，适合本地小规模使用。

## 构建和数据

使用 `npm ci` 与 Maven Wrapper。前端构建后执行 `mvnw.cmd -Pweb clean verify` 可合并打包；后端普通构建不需要 Node。切换模式使用 clean，避免静态产物残留。

Flyway 管理 SQL 版本。已发布迁移禁止就地修改，应增加新迁移。默认数据库位于进程工作目录的 data 下，禁止存放到 target（本地一次性验证数据库例外）。数据库文件已在 .gitignore 中排除。

V5 增加 harness_settings、harness_session.workspace，按最新历史任务回填会话工作区；harness_event.run_id 允许 NULL，用于会话 WORKSPACE 事件。工作区切换与事件追加同事务；活动任务占位存在时拒绝切换。提交在同一存储锁下将会话目录复制到 Run，后续设置不影响已提交任务。目录选择 API 只返回目录名/路径；实际模型工具仍受 LocalWorkspaceTools 的根目录、链接和排除规则约束。

V6 将 V5 单行配置原样复制为 harness_model_profile 的 default 条目（密文不重加密），然后移除旧表。默认条目不可删除；其他条目按 revision 编辑/删除，删除不级联清理会话、Run 或快照。会话记录最近成功提交的 model_profile_id 和 reasoning_effort。Run 记录实际解析后的选择及 requested_selection（原始可空选择），重试先核对原请求再解析配置；配置已更改/删除也不会重跑旧任务，复用 requestId 修改模型或强度返回 409。选中的配置已删除时，新请求返回 404，不回退到其他模型。

ModelSelection 的 null 表示省略选择：profileId 默认 default，reasoningEffort 使用模型配置的默认值；空字符串强度表示明确省略上游 reasoning_effort。非空值只允许 none/minimal/low/medium/high/xhigh，按值发送到每一步 Chat Completions 请求。该字段不依赖具体模型名，不推断服务能力；不支持时由上游明确拒绝。旧 ModelRequest JSON 缺省强度为空。前端在输入框旁选择，每次请求及待确认重试体都冻结这两个字段，不受后续页面操作影响。

V7 创建提供商表及模型外键；JdbcSettingsStore 构造时在事务中处理 provider_id 为空的旧条目：解密后比较规范化基础地址、Key、鉴权要求和连接超时，复用相同连接的提供商；将原密文移入提供商，模型 settings_json 改为不含连接字段的 ModelParameters。迁移失败整体回滚，重启可重试；不能用密文相同判断 Key 相同，因为 AES-GCM 每次 nonce 不同。

提供商保存按 revision 更新，并在同一事务递增关联模型版本，防止旧页面覆盖新的组合配置。删除仍有模型引用的提供商返回 409；允许先移动模型再删除。模型保存不修改提供商。RunConfiguration 在提交时解析并捕获提供商和模型参数，后续提供商更新、模型移动或删除不改变活动任务。兼容 /api/settings 仍可编辑默认模型及其提供商；该旧接口修改连接字段会影响同提供商的其他模型，页面使用拆分后的接口。

V3 增加 harness_model_request 和 Run 的 context_summary。快照插入、摘要更新与 CONTEXT 事件同一事务提交；唯一 run_id 防止覆盖。旧记录保持 context=null，GET 快照返回 204。消息快照按需读取，普通会话快照只包含计数摘要，避免在每次 SSE 刷新时重复加载所有历史请求。

V4 增加工作区、后续步骤快照、工具执行与成功任务完整对话表。第一步保留 V3 存储及 GET 单快照接口；新增 GET model-requests 和 tools 接口。工具状态变化提交 TOOL 事件，前端按通知读取工具记录；模型输入只在展开时读取并随步骤刷新。终态取消/恢复会同步结算未完成工具。区分协议解析安全上限（256 个调用）与模型配置的单次执行上限（默认 32，最大 256）；超出执行上限的调用从 PENDING 进入 SKIPPED，保存配对结果而不实际执行，下一次响应可由模型重新请求。索引仅标识调用，不能以 index >= 执行上限判断协议无效。

历史候选以当前 Run 的 QUEUED 事件为界，按 seq 顺序查询，排除绑定其他工作区的轮次。每次组装读取该会话此前的 Run，适合本地小规模使用；发送内容受预算约束，数据库历史读取尚未分页。ModelMessage 校验完整工具配对，裁剪不可拆分一个用户轮次中的工具循环。工具输出是不可信数据，不能扩展工具权限；目录边界和排除规则不是操作系统沙箱。

本阶段使用引擎级协调锁和单例存储锁，H2 文件由一个进程持有。不能将当前设计直接扩展为多 JVM 共享库，也不提供 exactly-once 外部副作用语义。

AgentInstructions 在工具模式为每一步生成证据指引，随 ModelRequest.instructions 保存，以首条 system 消息发送；它不进入成功对话历史。默认不设轮次上限，不产生到限收尾请求；正整数配置才启用预算指引和 ModelRequest.finalAnswerOnly，并发送标准 tool_choice=none。字段缺省兼容旧 JSON，step 不再有 32 的校验上限。终态成功的 detail 区分显式预算耗尽后的回答，不能把“已完成”解释为源码问题完全查明。目录树默认深度 3、最大 8，全树共用 200 项/2000 扫描上限；较大读取行区间自动收窄并提示，安全边界不放宽。

## 验证

`harness-server/src/test` 使用实际 H2、迁移和引擎验证状态与持久化；HTTP 集成测试启动随机端口的 Spring Boot 服务并检查 SSE。测试数据库隔离，不使用 data 下的用户数据库。

OpenAiChatCompletionTest 使用随机端口的本地 HTTP 服务验证通用适配器的最小请求字段、路径前缀、Bearer / 无鉴权、任意模型名、断流、取消传播、无自动重试以及旧数据保留。使用占位 Key，不访问公网模型。真实账户联调未执行。GitWorkspaceToolsTest 在 `@TempDir` 内创建真实仓库（git 不可用时 abort），覆盖仓库根判定、分支与各类状态的渲染、重命名路径顺序、排除路径在状态与差异中均不出现、单文件补丁与暂存/未暂存区分、目录与通配符与越界路径拒绝、中文与空格文件名、大补丁与大列表的截断边界、取消与截止时间停止子进程，以及非 git 调用仍正确委托。文件编辑、鉴权审批、插件及自动压缩仍未实现；git 工具只读，不提供 add/commit/checkout 或 worktree 隔离。
