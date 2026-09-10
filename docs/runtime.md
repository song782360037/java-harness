# 最小运行闭环

本版是本机单用户的模型流执行器，固定使用 OpenAI Chat Completions 兼容接口，支持同会话多轮上下文，无模拟运行模式。一个 Session 保存多次 Run；每个 Run 发起一次模型流，一个 Session 同时只允许一个活动 Run。不同 Session 可以独立执行。

## 调用链

```text
Web 提交输入（持有 requestId）
→ SessionController.submit
→ DefaultAgentEngine.submit
→ JdbcSessionStore.submit（事务：Run + 活动执行占位 + QUEUED 事件）
→ 虚拟线程：RUNNING → contextHistory（QUEUED seq 之前的记录）
→ ConversationContext.build（过滤、完整轮次裁剪、冻结 ModelRequest）
→ saveModelRequest（事务：消息快照 + context_summary + CONTEXT 事件）
→ OpenAiChatCompletionProvider.stream（直接发送冻结消息）
→ 每次分段回调：事务追加 TEXT 事件并更新输出
→ 最终事务：终态 + STATE 事件 + 释放会话占位

EventController → 按 seq 读取数据库事件 → SSE
→ Web 合并读取一致快照 → 展示输出与状态
```

每次数据库变化后才可被 SSE 读取。当前 TEXT 分段全部持久化，UI 使用的输出是已提交内容。存储是单进程实例，状态投影和事件表一起提交。H2 设置 WRITE_DELAY=0；未完成事务的文字不属于已提交记录。

## 状态

```text
QUEUED → RUNNING → COMPLETED / FAILED
QUEUED 或 RUNNING → CANCELLING → CANCELLED / TIMED_OUT
任意活动状态在进程异常退出后的启动恢复中 → INTERRUPTED
```

执行器提交失败也可使 QUEUED → FAILED。所有终态不可复活。

用户取消与超时由同一个协调入口仲裁，先到者确定停止原因。停止首先提交 CANCELLING，然后发送中断；只有模型调用返回、退出或抛出后才写最终状态。已取消任务的迟到回调不保存，不会形成 COMPLETED。

关闭页面或 SSE 连接不取消 Run。正常退出服务请求取消并等待收尾；异常退出后不恢复工作线程，只补写 INTERRUPTED 事件并释放活动占位，不重复执行。对于忽略取消的未来第三方模型，不能凭线程中断宣称它已经停止。

## API

所有请求和响应为 JSON，SSE 除外。POST 必须携带 `Content-Type: application/json`。当前没有登录体系；API 拒绝非本地 Host 与不允许的浏览器 Origin，允许本地后端端口及 Vite 5173。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/model` | `{ provider, model, configured }`；仅配置摘要，不返回 Key / URL，也不代表连接验证成功 |
| GET | `/api/runs/{id}/model-request` | 200 返回冻结的 ModelRequest；旧记录 / 尚未准备快照返回 204；Run 不存在返回 404 |
| POST | `/api/sessions` | `{ "title": "新会话" }`，201 返回 Session |
| GET | `/api/sessions` | 会话列表，创建时间倒序 |
| GET | `/api/sessions/{id}` | `{ session, runs, lastSeq }` 一致快照 |
| POST | `/api/sessions/{id}/runs` | 新请求 202；同内容幂等重试 200；会话忙碌 409 |
| POST | `/api/runs/{id}/cancel` | JSON 空对象；200 返回当前状态，可为 CANCELLING 或既有终态 |
| GET | `/api/sessions/{id}/events?after=0` | SSE；若有 Last-Event-ID 则优先使用该值 |

提交示例：

```json
{
  "requestId": "096b0fd5-2c26-4734-8d81-55d7d1d8ef21",
  "prompt": "你好"
}
```

requestId 必须为标准小写 UUID；唯一键是 `(session_id, request_id)`。重试同 ID 返回原 Run，包含终态 Run；更换 prompt/scenario 却复用 ID 返回 409。prompt 为 1–4000 字符。scenario 仅作为旧记录和待确认请求的兼容字段保留，新请求省略或使用 NORMAL；FAIL / SLOW 不能创建新任务，旧请求同内容重试仍返回原记录。所有输出上限 100000 字符。

Run 额外保存 `provider` 和 `model`；由服务配置决定，客户端不提交。新真实调用的 provider 为 openai-compatible，表示适配协议，不能用于判定实际厂商或服务地址；model 为配置的模型名。V2 对 V1 记录补写 simulated / local-simulator，已有 DeepSeek 记录保留原标识。重启切换模型后，既有 requestId 重试仍返回原记录，不再次调用模型。模型名缺失、要求 Key 但未填写或真实模式传入模拟场景返回 400，不创建 Run。

错误返回 `{ code, message }`；无效输入 400，不存在 404，忙碌或状态冲突 409，正在停止/连接容量不足 503。

前端先保存待确认请求，再发送。请求结果不明时保留 requestId，重试不会创建新 Run；刷新后如快照中已存在该请求则自动清除待确认项。不能更改待确认请求的内容后复用其 ID。

## SSE

事件名为 `run-event`，事件 id 是会话内单调 seq。示例：

```text
id:3
event:run-event
data:{"sessionId":"...","seq":3,"runId":"...","type":"TEXT","status":null,"text":"已收到","createdAt":"..."}
```

STATE 事件携带 status 与 detail（使用 text 字段），TEXT 事件只携带增量文本，CONTEXT 事件的 text 为上下文计数摘要 JSON，status=null。客户端只处理大于已应用 seq 的通知，再读取完整投影，不重复拼接文本。超出当前 lastSeq、负数或非法游标返回 400。

连接自然结束后 EventSource 自动重连；SSE 消费不改变运行状态。事件轮询从上次游标直接读库，无需在内存消息订阅与数据库补发之间切换，因此不会漏掉补发期间的新事件。

## 明确边界

这是一次模型流的执行生命周期，包含历史消息投影，不包含完整 Agent 工具循环、自动重试、工作区操作、审批和插件。会话事件能重建当前文本与状态，但没有引入未来工具调用的可重放副作用。

OpenAiChatCompletionProvider 通过 JDK HttpClient 请求基础地址加 `/chat/completions`，保留 `/v1` 及网关前缀。请求仅包含 model、stream=true、messages（冻结的历史 user/assistant 问答 + 当前 user）；不添加厂商扩展、固定 max_tokens 或 max_completion_tokens，使用上游默认生成参数。不保存 reasoning_content。Key 非空时使用 Bearer 鉴权，Key 为空且显式关闭必填要求时不发送 Authorization。模型 SSE 与发往浏览器的会话 SSE 是两个独立协议。

历史仅选取同会话、早于当前提交、COMPLETED、非 simulated 且 output 非空白的记录。模型或服务切换不切断会话历史。以 32000 UTF-16 字符的默认正文预算保留合格轮次的最近连续后缀，超限移除完整旧轮次，不切分消息，不自动摘要；当前输入始终保留。该预算不计协议开销，也不是模型 token 窗口。

Run.context 包含 includedTurns（带入历史轮数）、droppedTurns（预算裁剪轮数）、excludedTurns（状态、模拟或空回答过滤数）、characters、maxCharacters。ModelRequest 包含 runId、model、scenario、messages、context，不含 Key / URL。快照在 RUNNING 阶段、发起网络请求前保存；排队即取消不会创建快照，运行中取消可能已有快照但不代表上游收到。快照写入失败回滚摘要和 CONTEXT 事件，Run 失败且不调用模型。终态或重复写入不能覆盖既有快照。

上游需同时返回 `finish_reason=stop` 和 `[DONE]` 才算正常完成。支持角色分段、空内容和 usage-only 分段。意外 EOF、无效 JSON、HTTP 错误、非 SSE 响应、超长事件、长度截断、内容过滤、refusal 和未支持的工具调用均失败并保留已提交文本；空 tool_calls 数组不算工具调用。单个上游事件最多 65536 个 UTF-16 字符；回答按不拆分代理对的 4096 字符分段入库。上游错误正文不进入用户错误信息或日志。

HTTP 响应头等待和响应体等待均可由引擎中断，退出时取消响应体订阅。总时间预算默认 120 秒，覆盖连接、响应等待和生成；单独连接超时 10 秒。客户端结束不代表服务商撤销已发生的推理或计费。

该版本采用单进程串行存储和完整快照读取，尚未实现大规模历史分页、跨进程协调、多用户认证及生产压力验证。
