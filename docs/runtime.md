# 运行闭环

本版面向本机单用户，使用 OpenAI Chat Completions 兼容接口，支持同会话多轮上下文、工作区工具循环及文件修改审核，无模拟运行模式。一个 Session 保存多次 Run；一个 Run 可包含多次模型请求，每个 Session 同时只允许一个活动 Run。不同 Session 可以独立执行。

## 调用链

```text
Web 提交输入（持有 requestId）
→ SessionController.submit → DefaultAgentEngine.submit
→ JdbcSessionStore.retry（先识别原请求；复用 ID 改模型/强度返回冲突）
→ SettingsService.snapshot（按本次选择捕获模型、思考强度、连接/执行超时、上下文及步数配置）
→ JdbcSessionStore.submit（事务：复制会话工作区到 Run + 保存选择 + 活动占位 + QUEUED 事件）
→ 虚拟线程：RUNNING → 根据捕获配置创建模型客户端和工作区工具
→ contextHistory → ConversationContext.build（完整轮次过滤、裁剪）
→ 每个模型步骤：
    检查取消与上下文容量
    → saveModelRequest（事务：不可覆盖的步骤快照 + context_summary + CONTEXT 事件）
    → OpenAiChatCompletionProvider.complete（标准 HTTP/SSE）
    → 文本回调：事务追加 TEXT 事件并更新输出
    → 无工具调用：保存完整本轮对话并完成
    → 有工具调用：保存整批 PENDING + TOOL 事件
      → 依序 RUNNING → LocalWorkspaceTools.execute → COMPLETED / FAILED
      → 追加与 tool_call_id 配对的 tool 消息，进入下一步
→ 最终事务：终态 + STATE 事件 + 释放会话占位

EventController → 按 seq 读取数据库事件 → SSE
→ Web 读取一致快照，刷新工具卡片及已展开的步骤输入
```

模型 `complete` 必须返回完整本步文本与调用列表，并把文本通过回调分段输出；兼容旧文本适配器的默认实现包装 `stream`。只有接收到完整结束标记，HTTP 适配器才返回调用，分片、无效或提前断开的调用不会交给工具执行器。工具定义由服务端提供，模型不能新增可执行能力。

## 状态与停止

```text
QUEUED → RUNNING → COMPLETED / FAILED
QUEUED 或 RUNNING → CANCELLING → CANCELLED / TIMED_OUT
任意活动状态在进程异常退出后的启动恢复中 → INTERRUPTED
工具：PENDING → RUNNING → COMPLETED / FAILED
超出本步执行预算的工具：PENDING → SKIPPED（结果回传模型，可后续重新请求）
未完成工具随任务终止 → CANCELLED；异常重启 → INTERRUPTED
```

执行器提交失败也可使 QUEUED → FAILED。终态不可复活。用户取消与总超时由同一个协调入口仲裁，先到者确定停止原因：先提交 CANCELLING，再发送中断；工作线程退出后才写最终状态。迟到文本和工具结果丢弃，后续工具及模型请求不再启动。取消生效前已进入的本地读取可能仍在收尾。

关闭浏览器或 SSE 连接不会停止 Run。正常关闭服务会请求取消，先关闭 SSE 再关闭 Web 服务；异常退出后仅标记 INTERRUPTED、释放占位，不重新请求模型或重跑工具。文件工具在读取与遍历之间检查取消及截止时间，不能把中断等同于强制停止所有操作系统 I/O。

## API

所有请求和响应为 JSON，SSE 除外。POST 必须携带 `Content-Type: application/json`。API 拒绝非本地 Host 与不允许的浏览器 Origin，允许本地后端端口及 Vite 5173，尚无登录体系。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/model` | `{ provider, model, configured }`，不返回 Key / URL，不代表连接验证成功 |
| GET | `/api/settings` | 兼容旧接口：仅 default 模型配置及 revision、apiKeyConfigured；不返回 Key 明文或密文 |
| POST | `/api/settings` | 兼容旧接口：编辑 default 配置，保留其名称/默认思考强度；版本冲突 409 |
| GET | `/api/model-providers` | 提供商列表，含 id/revision/displayName/baseUrl/apiKeyConfigured/apiKeyRequired/connectTimeoutSeconds；不返回 Key |
| POST | `/api/model-providers` | 新增提供商，revision=0；201 返回提供商 |
| POST | `/api/model-providers/{id}` | 更新共享连接及 Key，影响关联模型的新任务；旧版本 409 |
| POST | `/api/model-providers/{id}/delete` | `{ "revision": 1 }`；有模型引用时 409，空提供商可以删除 |
| GET | `/api/model-profiles` | 全部模型配置，包含 id/displayName/reasoningEffort/revision 和无 Key 的配置字段 |
| POST | `/api/model-profiles` | 新增模型配置，revision=0；201 返回配置 |
| POST | `/api/model-profiles/{id}` | 编辑该配置，revision 必须匹配；200 返回配置，冲突 409 |
| POST | `/api/model-profiles/{id}/delete` | `{ "revision": 1 }`；保留历史和活动任务，default 不可删除 |
| GET | `/api/workspace?sessionId={id}` | 会话的 `{ configured, root, tools }`；不传 sessionId 返回未配置 |
| GET | `/api/workspaces/directories?path=...` | 返回 path、parent、directories、truncated；空 path 返回磁盘/根目录 |
| GET | `/api/runs/{id}/model-request` | 保留兼容：200 返回第一步快照；尚无快照 204；Run 不存在 404 |
| GET | `/api/runs/{id}/model-requests` | 按 step 排序的全部 ModelRequest；尚无快照 `[]`；Run 不存在 404 |
| GET | `/api/runs/{id}/tools` | 按步骤和调用顺序返回 ToolExecution；尚无工具 `[]`；Run 不存在 404 |
| GET | `/api/runs/{id}/file-changes` | 文件修改提案，包含原内容、新内容、路径及状态；未知 Run 404 |
| POST | `/api/file-changes/{id}/apply` | JSON 空对象；正常完成且原工作区空闲时应用；200 返回 APPLIED/CONFLICT/UNCERTAIN，前端必须检查状态；不可操作的记录或会话返回 409 |
| POST | `/api/file-changes/{id}/reject` | JSON 空对象；PENDING → REJECTED，重复拒绝幂等；已处理为其他状态时 409 |
| POST | `/api/sessions` | `{ "title": "新会话", "workspace": "绝对路径或空字符串" }`，201 返回 Session；省略 workspace 为仅聊天 |
| POST | `/api/sessions/{id}/workspace` | `{ "workspace": "绝对路径或空字符串" }`，返回更新后 Session；会话有活动任务时 409 |
| GET | `/api/sessions` | 会话列表，创建时间倒序 |
| GET | `/api/sessions/{id}` | `{ session, runs, lastSeq }` 一致快照 |
| POST | `/api/sessions/{id}/runs` | 新请求 202；同内容幂等重试 200；会话忙碌 409 |
| POST | `/api/runs/{id}/cancel` | JSON 空对象；200 返回 CANCELLING 或既有终态 |
| GET | `/api/sessions/{id}/events?after=0` | SSE；Last-Event-ID 优先 |

提交为 `{ "requestId": "标准小写 UUID", "prompt": "分析项目入口", "modelProfileId": "default", "reasoningEffort": "high" }`。modelProfileId 省略/null 使用 default 配置，reasoningEffort 省略/null 使用该配置默认强度，空字符串明确省略上游参数。唯一键是 `(session_id, request_id)`；同 ID 同内容返回原 Run，含终态，即使配置已删除也不重复运行。复用 ID 修改 prompt/scenario/模型选择/强度返回 409。prompt 为 1–4000 字符。scenario 仅用于旧记录兼容，新请求省略或使用 NORMAL；FAIL / SLOW 不能创建真实任务。

Session 保存 id、title、createdAt、workspace、modelProfileId、reasoningEffort，后两项为最近一次成功提交的选择。页面可选择本机现有可读目录，服务端规范化为绝对路径，模型工具仅访问该根目录。Run 保存 provider、model、context、workspace、modelProfileId、reasoningEffort，绑定提交时选择的配置和会话目录。新 provider 为 openai-compatible，不能据此判定实际厂商；旧来源标识保留。ModelRequest 包含 runId、model、scenario、messages、context、step、workspace、tools、instructions、finalAnswerOnly、reasoningEffort。instructions 以首条 system 消息发送，但不写入历史问答；finalAnswerOnly=true 时发送标准 tool_choice=none。页面展示系统指引、思考强度及“收尾回答”标记，消息条数包含系统消息。旧 JSON 的新字段默认为空字符串/false。ToolExecution 包含 runId、step、callId、name、arguments、status、output。快照不含鉴权配置；HTTP 适配器将 toolCalls/toolCallId 映射为标准 tool_calls/tool_call_id。

配置保存请求包含 revision、baseUrl、modelName、apiKey、clearApiKey、apiKeyRequired、connectTimeoutSeconds、runTimeoutSeconds、contextMaxCharacters、maxSteps、toolTimeoutSeconds。apiKey 省略/空白表示保留，非空替换；clearApiKey=true 清除，不能同时提交新 Key。响应包含除 apiKey/clearApiKey 外的配置字段，并增加 apiKeyConfigured 和 model（ModelInfo）。保存不调用上游、不证明连通性；缺少模型名或必需 Key 时仍可保存，但不能提交新任务。字段范围及兼容地址示例见 README 的页面配置说明。

model-providers 新增/编辑请求为 revision、displayName、baseUrl、apiKey、clearApiKey、apiKeyRequired、connectTimeoutSeconds，Key 保留/清除规则同上。新增 provider 时省略 Key 不继承其他提供商的 Key。

model-profiles 新增/编辑请求为 revision、providerId、displayName、modelName、reasoningEffort、runTimeoutSeconds、contextMaxCharacters、maxSteps、toolTimeoutSeconds；响应增加 id、providerName 和 model（ModelInfo），不含 baseUrl/Key/连接超时。新增同提供商的模型只需引用 providerId，使用该提供商共享连接。界面提供服务默认、none/minimal/low/medium/high/xhigh；服务默认不发送 reasoning_effort，其余透传，不保证任意兼容服务均支持。

错误返回 `{ code, message }`：无效输入 400，不存在 404，忙碌/状态冲突 409，正在停止/连接容量不足 503。前端持久化待确认 requestId，网络结果不明时以同 ID 重试，避免重复提交。

## 持久化与事件

H2 单进程存储采用同步锁与事务；投影、序号和事件一起提交，WRITE_DELAY=0。旧迁移不改写，V4 增加：

- `harness_run.workspace`：任务绑定目录，旧记录为空。
- `harness_model_step`：后续步骤快照，主键 `(run_id, step)`；第一步继续使用 V3 `harness_model_request`。
- `harness_tool`：工具参数、状态、结果和顺序，主键 `(run_id, call_id)`。
- `harness_conversation`：成功任务的完整本轮 user/assistant/tool 对话，与 COMPLETED 一起提交；不重复保存此前历史。

旧 V3 JSON 缺省 step=1、workspace 为空、tools/toolCalls 为空。无快照的旧记录不补造快照；无完整对话记录的旧任务，使用原 prompt/output 恢复普通问答历史。

V5 增加 harness_settings（单行、revision 和含加密 Key 的配置 JSON）及 harness_session.workspace；按最近任务回填会话目录，无任务为空。harness_event.run_id 允许 NULL，WORKSPACE 事件不关联任务。环境变量仅初始化空配置表，后续重启读取持久化配置；安装密钥和 H2 文件需配套恢复。

V6 将单行配置及原密文迁移到 harness_model_profile，id 为 default，随后移除 harness_settings。Session 保存最近选择，Run 保存实际选择和原始 requested_selection，以支持配置变化后的精确幂等重试；旧任务选择缺省为 default / 服务默认，旧原始选择为 null。模型配置与历史记录不设级联外键，删除配置不会影响活动任务或历史。

V7 增加 harness_model_provider，模型通过 provider_id 引用共享连接。启动时事务性拆分旧 JSON，相同地址/Key/鉴权要求/连接超时合并为一个提供商，模型 JSON 仅保留模型参数。Key 保留原密文，安装密钥继续使用。提供商更新同时递增关联模型版本；活动任务使用已捕获的不可变配置，不受影响。

SSE 事件名 `run-event`，id 为会话内单调 seq。STATE 携带状态与原因，TEXT 携带已提交文本，CONTEXT 的 text 是上下文计数 JSON；TOOL 通知工具投影变化，前端读取工具 API；WORKSPACE 通知会话目录变更，runId 为 null。终态 STATE 也触发工具刷新，以显示取消/中断。客户端只用大于已应用 seq 的通知刷新，不重复拼接输出；支持数据库补发，断线不会改变任务状态。

## 上下文与协议边界

历史选取同会话、早于当前提交、COMPLETED、非 simulated、output 非空白且 workspace 为空或匹配当前目录的记录。切换模型/服务不切断合格历史；切换工作区会排除绑定其他目录的记录。按提交事件 seq 排序，按完整问答轮次保留最近后缀，工具调用与结果成对保留。

默认 32000 UTF-16 字符预算包含系统指引、正文、调用 ID、名称、参数和工具结果，配置范围 4000–256000；不计工具定义及协议开销，也不是 token 计数。每步在加入本步系统指引后校验容量，超限则失败，禁止下一次模型请求，不截掉未配对结果。context 的 includedTurns/droppedTurns/excludedTurns 是历史轮数，characters 是本步全部消息字符数。

每步调用前保存快照，失败时不请求模型；快照存在不证明上游成功接收。页面 maxSteps 默认 0，不设固定请求轮数；ModelRequest.step 也不再限制为 32，后续步骤按原表逐条持久化。默认系统指引说明可根据证据继续调查，建议 Java 入口直接 search_files、目录树使用 depth=8，不按轮数强制终止或发送 tool_choice=none。

显式配置正整数步数上限时，才在最后一步通过 tool_choice=none 关闭调用，要求根据已有结果回答并说明未确认项；成功时 COMPLETED 和探索上限提示与完整对话同事务保存。若返回空白文字或无视 tool_choice 继续请求工具，明确失败；后者调用保存为 SKIPPED，不执行。取消、总超时、上下文和输出限制在两种模式下都生效；不设轮次上限不代表无限时间或无限上下文。

探索步每步最多实际执行 8 个工具。若模型返回更多调用，前 8 个按索引顺序执行，其余保存为 SKIPPED，不访问文件系统，但仍追加对应 tool_call_id 的结果，说明本步未执行及后续请求方法。工具输入错误作为 FAILED 结果回传；读取行区间超过 200 行会自动限制为前 200 行并提示继续位置。没有自动 HTTP 重试或重启续跑。

HTTP 请求基础地址追加 `/chat/completions`，保留网关前缀。无工作区仅发送 model、stream=true、messages；有工作区额外发送标准函数 tools。支持流式 tool_calls 的 index/id/function.name/function.arguments 分片累积，按 index 排序；索引必须为非负 int，可不连续，其数值不代表调用数量。单步最多累积 32 个不同索引（跨所有分片），超过时失败且不执行该批，避免无限累积。每个 ID 最多 128 字符、名称 64、参数 16384，拒绝重复 ID、非法索引和类型，并分别提示 index、type 或数量上限，避免混淆。工具参数必须是 JSON 对象且只含定义字段。正常文本需要 finish_reason=stop + [DONE]；工具步骤需要非空调用、finish_reason=tool_calls + [DONE]。

支持角色、空内容及 usage-only 分段。EOF、缺失结束标记、非法 JSON、超长事件、HTTP 错误、非 SSE、长度截断、过滤和 refusal 都会失败并保留已提交文本。单事件最大 65536 字符，回答按不拆分代理对的 4096 字符分段入库，总输出最多 100000 字符。不保存 reasoning_content，不附加厂商扩展或固定生成 token 上限；仅在用户选择具体强度时发送标准 reasoning_effort。

默认总超时 120 秒覆盖整个循环，HTTP 连接超时 10 秒，单工具默认 5 秒。终止本机请求不能保证撤销上游计费。读取工具在操作之间检查截止时间；文件提案在进入持久化前检查取消、并在存储锁内再次校验 RUNNING，不保证强行中断磁盘或数据库 I/O。工具目录、排除规则、预算及配置方法见 README“启用工作区”。本版支持文件修改审核与命令审批，没有通用审批扩展、多用户隔离、操作系统沙箱或跨进程协调，不应用于不可信进程可恶意改写的文件系统。

文件提案独立于 Run 终态保存。ReviewWorkspaceTools 的 propose_file_change 只创建 PENDING 记录并回传提案 ID；Run 继续回答，不等待用户审批，也不因应用动作自动续跑。审核 API 通过 FileChangeService 校验 Run 正常完成、会话空闲及工作区一致。每次提案/处理变化提交 FILE_CHANGE 事件；恢复连接及刷新页面可重新读取完整记录。文件被改动时 CONFLICT 是持久化审核结果，不是成功写入；UNCERTAIN 需要检查文件并重新生成提案。模型下次使用工具重新读取磁盘，不将历史待审核提案视为已应用。

## 命令生命周期

V9 的 harness_command 固定保存命令、用途、规范化目录、shell、时限与调用 ID。`propose_command` 在工具线程内等待审批，不持有引擎或存储锁等待；Run 保持 RUNNING，因此仍占据会话并可被取消。审批等待计入 Run 总时限。

状态为 PENDING → APPROVED → RUNNING → COMPLETED / FAILED / TIMED_OUT / OUTPUT_LIMIT。PENDING 可 REJECTED；运行中停止先 STOPPING，实际结束后 CANCELLED。任务取消/超时会中断等待或执行，清理进程后结算命令；故障无法确认结果时 INTERRUPTED。启动时将所有 PENDING / APPROVED / RUNNING / STOPPING 统一标记 INTERRUPTED，保留输出，不恢复审批或重新执行。

只有持有原始工具调用的线程能消费批准，启动进程前先提交 RUNNING 意图；重复批准 API 不启动进程。SQL 与进程启动不是单一事务，崩溃窗口的状态不代表命令一定已启动或未执行。stdout/stderr 由独立线程读取，合计限 32000 UTF-16 字符，每约 150 ms 有变化时持久化并提交 COMMAND 事件。页面根据事件拉取快照，恢复连接和刷新时重新获取；输出按文本显示，不执行 HTML。

API 为 GET `/api/runs/{id}/commands`，以及 POST JSON `/api/commands/{id}/approve`、`reject`、`stop`。沿用本机 Host/Origin 保护，不提供模型侧批准工具。命令可操作服务账户可访问的资源，不属于文件工具的排除路径沙箱；环境不会主动注入模型 Key，并移除继承的 HARNESS_MODEL_API_KEY，但不能隔离账户本来可访问的其他凭据。
