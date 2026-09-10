# 最小运行闭环

本版是本机单用户的模拟模型执行器。一个 Session 保存多次 Run；一个 Session 同时只允许一个活动 Run。不同 Session 可以独立执行。

## 调用链

```text
Web 提交输入（持有 requestId）
→ SessionController.submit
→ DefaultAgentEngine.submit
→ JdbcSessionStore.submit（事务：Run + 活动执行占位 + QUEUED 事件）
→ 虚拟线程：RUNNING → SimulatedModelProvider.stream
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
  "prompt": "你好",
  "scenario": "NORMAL"
}
```

requestId 必须为标准小写 UUID；唯一键是 `(session_id, request_id)`。重试同 ID 返回原 Run，包含终态 Run；更换 prompt/scenario 却复用 ID 返回 409。prompt 为 1–4000 字符；scenario 为 NORMAL、FAIL、SLOW，省略默认 NORMAL。模拟输出上限 100000 字符。

错误返回 `{ code, message }`；无效输入 400，不存在 404，忙碌或状态冲突 409，正在停止/连接容量不足 503。

前端先保存待确认请求，再发送。请求结果不明时保留 requestId，重试不会创建新 Run；刷新后如快照中已存在该请求则自动清除待确认项。不能更改待确认请求的内容后复用其 ID。

## SSE

事件名为 `run-event`，事件 id 是会话内单调 seq。示例：

```text
id:3
event:run-event
data:{"sessionId":"...","seq":3,"runId":"...","type":"TEXT","status":null,"text":"已收到","createdAt":"..."}
```

STATE 事件携带 status 与 detail（使用 text 字段），TEXT 事件只携带增量文本。客户端只处理大于已应用 seq 的通知，再读取完整投影，不重复拼接文本。超出当前 lastSeq、负数或非法游标返回 400。

连接自然结束后 EventSource 自动重连；SSE 消费不改变运行状态。事件轮询从上次游标直接读库，无需在内存消息订阅与数据库补发之间切换，因此不会漏掉补发期间的新事件。

## 明确边界

这是一次模型流的执行生命周期，不包含完整 Agent 工具循环、真实模型、历史上下文理解、自动重试、工作区操作、审批和插件。会话事件能重建当前文本与状态，但没有引入未来工具调用的可重放副作用。

该版本采用单进程串行存储和完整快照读取，尚未实现大规模历史分页、跨进程协调、多用户认证及生产压力验证。
