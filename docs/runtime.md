# 运行闭环

本版面向本机单用户，使用 OpenAI Chat Completions 兼容接口，支持同会话多轮上下文及只读工具循环，无模拟运行模式。一个 Session 保存多次 Run；一个 Run 可包含多次模型请求，每个 Session 同时只允许一个活动 Run。不同 Session 可以独立执行。

## 调用链

```text
Web 提交输入（持有 requestId）
→ SessionController.submit → DefaultAgentEngine.submit
→ JdbcSessionStore.submit（事务：Run + 活动占位 + QUEUED 事件）
→ 虚拟线程：RUNNING → 绑定配置工作区
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
| GET | `/api/workspace` | `{ configured, root, tools }`，工具名列表及规范化路径 |
| GET | `/api/runs/{id}/model-request` | 保留兼容：200 返回第一步快照；尚无快照 204；Run 不存在 404 |
| GET | `/api/runs/{id}/model-requests` | 按 step 排序的全部 ModelRequest；尚无快照 `[]`；Run 不存在 404 |
| GET | `/api/runs/{id}/tools` | 按步骤和调用顺序返回 ToolExecution；尚无工具 `[]`；Run 不存在 404 |
| POST | `/api/sessions` | `{ "title": "新会话" }`，201 返回 Session |
| GET | `/api/sessions` | 会话列表，创建时间倒序 |
| GET | `/api/sessions/{id}` | `{ session, runs, lastSeq }` 一致快照 |
| POST | `/api/sessions/{id}/runs` | 新请求 202；同内容幂等重试 200；会话忙碌 409 |
| POST | `/api/runs/{id}/cancel` | JSON 空对象；200 返回 CANCELLING 或既有终态 |
| GET | `/api/sessions/{id}/events?after=0` | SSE；Last-Event-ID 优先 |

提交为 `{ "requestId": "标准小写 UUID", "prompt": "分析项目入口" }`。唯一键是 `(session_id, request_id)`；同 ID 同内容返回原 Run，含终态，不重复运行。复用 ID 修改 prompt/scenario 返回 409。prompt 为 1–4000 字符。scenario 仅用于旧记录兼容，新请求省略或使用 NORMAL；FAIL / SLOW 不能创建真实任务。

Run 保存 provider、model、context、workspace；模型及工作区由服务端配置决定，客户端不能选择任意目录。新 provider 为 openai-compatible，不能据此判定实际厂商；旧来源标识保留。ModelRequest 包含 runId、model、scenario、messages、context、step、workspace、tools、instructions、finalAnswerOnly。instructions 以首条 system 消息发送，但不写入历史问答；finalAnswerOnly=true 时发送标准 tool_choice=none。页面展示系统指引及“收尾回答”标记，消息条数包含系统消息。旧 JSON 的新字段默认为空字符串/false，无需额外 SQL 迁移。ToolExecution 包含 runId、step、callId、name、arguments、status、output。快照不含鉴权配置；HTTP 适配器将 toolCalls/toolCallId 映射为标准 tool_calls/tool_call_id。

错误返回 `{ code, message }`：无效输入 400，不存在 404，忙碌/状态冲突 409，正在停止/连接容量不足 503。前端持久化待确认 requestId，网络结果不明时以同 ID 重试，避免重复提交。

## 持久化与事件

H2 单进程存储采用同步锁与事务；投影、序号和事件一起提交，WRITE_DELAY=0。V1–V3 不改写，V4 增加：

- `harness_run.workspace`：任务绑定目录，旧记录为空。
- `harness_model_step`：后续步骤快照，主键 `(run_id, step)`；第一步继续使用 V3 `harness_model_request`。
- `harness_tool`：工具参数、状态、结果和顺序，主键 `(run_id, call_id)`。
- `harness_conversation`：成功任务的完整本轮 user/assistant/tool 对话，与 COMPLETED 一起提交；不重复保存此前历史。

旧 V3 JSON 缺省 step=1、workspace 为空、tools/toolCalls 为空。无快照的旧记录不补造快照；无完整对话记录的旧任务，使用原 prompt/output 恢复普通问答历史。

SSE 事件名 `run-event`，id 为会话内单调 seq。STATE 携带状态与原因，TEXT 携带已提交文本，CONTEXT 的 text 是上下文计数 JSON；TOOL 通知工具投影变化，前端读取工具 API。终态 STATE 也触发工具刷新，以显示取消/中断。客户端只用大于已应用 seq 的通知刷新，不重复拼接输出；支持数据库补发，断线不会改变任务状态。

## 上下文与协议边界

历史选取同会话、早于当前提交、COMPLETED、非 simulated、output 非空白且 workspace 为空或匹配当前目录的记录。切换模型/服务不切断合格历史；切换工作区会排除绑定其他目录的记录。按提交事件 seq 排序，按完整问答轮次保留最近后缀，工具调用与结果成对保留。

默认 32000 UTF-16 字符预算包含系统指引、正文、调用 ID、名称、参数和工具结果，配置范围 4000–256000；不计工具定义及协议开销，也不是 token 计数。每步在加入本步系统指引后校验容量，超限则失败，禁止下一次模型请求，不截掉未配对结果。context 的 includedTurns/droppedTurns/excludedTurns 是历史轮数，characters 是本步全部消息字符数。

每步调用前保存快照，失败时不请求模型；快照存在不证明上游成功接收。HARNESS_TOOLS_MAX_STEPS 默认 0，不设固定请求轮数；ModelRequest.step 也不再限制为 32，后续步骤按原表逐条持久化。默认系统指引说明可根据证据继续调查，建议 Java 入口直接 search_files、目录树使用 depth=8，不按轮数强制终止或发送 tool_choice=none。

显式配置正整数步数上限时，才在最后一步通过 tool_choice=none 关闭调用，要求根据已有结果回答并说明未确认项；成功时 COMPLETED 和探索上限提示与完整对话同事务保存。若返回空白文字或无视 tool_choice 继续请求工具，明确失败；后者调用保存为 SKIPPED，不执行。取消、总超时、上下文和输出限制在两种模式下都生效；不设轮次上限不代表无限时间或无限上下文。

探索步每步最多实际执行 8 个工具。若模型返回更多调用，前 8 个按索引顺序执行，其余保存为 SKIPPED，不访问文件系统，但仍追加对应 tool_call_id 的结果，说明本步未执行及后续请求方法。工具输入错误作为 FAILED 结果回传；读取行区间超过 200 行会自动限制为前 200 行并提示继续位置。没有自动 HTTP 重试或重启续跑。

HTTP 请求基础地址追加 `/chat/completions`，保留网关前缀。无工作区仅发送 model、stream=true、messages；有工作区额外发送标准函数 tools。支持流式 tool_calls 的 index/id/function.name/function.arguments 分片累积，按 index 排序；索引必须为非负 int，可不连续，其数值不代表调用数量。单步最多累积 32 个不同索引（跨所有分片），超过时失败且不执行该批，避免无限累积。每个 ID 最多 128 字符、名称 64、参数 16384，拒绝重复 ID、非法索引和类型，并分别提示 index、type 或数量上限，避免混淆。工具参数必须是 JSON 对象且只含定义字段。正常文本需要 finish_reason=stop + [DONE]；工具步骤需要非空调用、finish_reason=tool_calls + [DONE]。

支持角色、空内容及 usage-only 分段。EOF、缺失结束标记、非法 JSON、超长事件、HTTP 错误、非 SSE、长度截断、过滤和 refusal 都会失败并保留已提交文本。单事件最大 65536 字符，回答按不拆分代理对的 4096 字符分段入库，总输出最多 100000 字符。不保存 reasoning_content，不附加厂商扩展、固定生成 token 上限或思考配置。

默认总超时 120 秒覆盖整个循环，HTTP 连接超时 10 秒，单工具默认 5 秒。终止本机请求不能保证撤销上游计费。工具目录、排除规则、读取预算及配置方法见 README“启用只读工作区”。本版没有写文件、Shell、审批、多用户隔离、操作系统沙箱或跨进程协调，不应用于不可信进程可恶意改写的文件系统。
