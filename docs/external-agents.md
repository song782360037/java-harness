# 外部智能体（ACP v1）

Java Harness 支持通过 **Agent Client Protocol（ACP v1，stdio JSON-RPC 2.0）** 直接调用本机已安装的外部智能体（例如 Codex、Claude Code 或自定义 ACP 适配器）。在普通对话中以 `/<别名> <任务内容>` 开头发送消息时，该轮任务直连外部适配器执行，不经过 Java Harness 内置模型引擎；下一条普通消息仍由当前主模型继续。

## 前置准备

Java Harness **不会自动安装适配器，也不会向外部程序转交自身保存的模型 API Key**。请先按上游文档在本地安装并完成认证：

- **Codex**：安装 [Codex ACP](https://github.com/agentclientprotocol/codex-acp)（命令通常为 `codex-acp`）。
- **Claude Code**：安装 [Claude Agent ACP](https://github.com/agentclientprotocol/claude-agent-acp)（命令通常为 `claude-agent-acp`）。
- **自定义适配器**：任何实现 ACP v1（`protocolVersion: 1`）并支持 stdio 传输的本地可执行程序。

## 配置与测试

打开 **设置 → 扩展 → 外部智能体**，点击 **添加智能体**：

| 字段 | 说明 |
| --- | --- |
| 类型 | 可选 `Codex`、`Claude Code` 或 `自定义 ACP`；保存后类型不可修改。 |
| 名称 | 展示名称，1–80 个字符。 |
| 命令别名 | 对话中以 `/<别名>` 唤起，须为 1–32 个小写字母、数字或连字符；`codex` 与 `claude` 保留给对应内置预设。 |
| 可执行程序 | 填写 `PATH` 中可解析的程序名（例如 `codex-acp`、`claude-agent-acp`）或本地可执行文件的绝对路径。 |
| 启动参数 | 可选，每行填写一个参数，最多 32 项；不经过 Shell 解析，直接传给目标进程。 |
| 允许请求工作区写入 | 仅内置预设显示。关闭时使用只读/计划模式，勾选后切换为写入模式（详见下文权限模式）。 |
| 启用 | 启用后才会在输入框 `/` 菜单中可用并接受委派。 |

点击 **测试连接** 会在系统临时目录启动适配器并执行 `initialize` 握手，校验 `protocolVersion == 1`，返回适配器名称、版本及是否声明支持会话续接（`session/resume` 或 `session/load`）。

> **说明**：测试连接不会传递项目工作区数据、不会调用大模型，也**不验证账户认证状态**；若上游未完成认证，实际发送任务时会收到认证错误（ACP `-32000`）。

## 在对话中使用

1. 打开一个普通会话，并在底部选择**已绑定本地目录的工作区**（未选择工作区无法运行外部智能体）。
2. 在输入框键入 `/` 可从补全列表中选择已启用的外部智能体，或直接输入 `/codex ...`、`/claude ...`、`/自定义别名 ...`。
3. 同一会话里可以穿插使用外部智能体与主模型：
   - 以 `/<别名>` 开头且不含附件/文件引用的消息由对应外部智能体处理（任务文本上限 100,000 字符）；
   - 普通消息仍由会话主模型处理；
   - 运行期间会实时展示流式文本输出与外部工具调用卡片（单次运行最多展示 200 条外部工具调用，输出累计上限 100,000 字符）。

### 权限模式与交互

内置预设在建立或恢复会话后会强制检查并切换模式（若适配器未提供目标模式，任务会被拒绝，不会悄悄降级运行）：

| 预设 | 默认（未勾选写入） | 勾选「允许请求工作区写入」 |
| --- | --- | --- |
| `codex` | `read-only`（同时传入 `INITIAL_AGENT_MODE=read-only`） | `workspace-write`（同时传入 `INITIAL_AGENT_MODE=workspace-write`） |
| `claude` | `plan` | `default` |
| `custom` | 由适配器自身配置决定 | 由适配器自身配置决定 |

运行期间，外部智能体可发起两类反向交互请求，界面会在运行时间线中渲染交互卡片，且同步计入顶栏**任务中心**与桌面系统通知：

- **权限确认（`session/request_permission`）**：仅展示一次性选项（`allow_once` / `reject_once`）。对未勾选写入的 `codex` / `claude` 预设，涉及 `edit` 或 `switch_mode` 的请求不允许批准；标题超 500 字符或详情超 16,000 字符的请求同样禁止批准，防止截断误批。
- **结构化追问（`elicitation/create`，`mode: "form"`）**：支持最多 12 个标量字段（`string`、`boolean`、`number`、`integer` 及字符串 `enum`）的表单；超出支持范围的 Schema 会自动拒绝。

单次运行最多记录 40 条交互请求；每条待处理请求等待 **5 分钟**，超时或任务取消后自动拒绝/取消。

## 会话续接与生命周期

- **自动续接**：若适配器在 `initialize` 阶段声明支持 `session/resume` 或 `session/load`，任务正常结束（`end_turn`、`max_tokens`、`max_turn_requests`）后，系统会按 `(会话 ID, 智能体 ID, 工作区目录指纹)` 记住远端 `sessionId`，下次在同一会话与同一工作区再次调用该智能体时自动续接。
- **何时重置**：
  - 修改了该智能体的类型、可执行程序、启动参数或写入开关后，旧的续接记录会被清除；
  - 若远端会话已过期或不存在（ACP `-32002`），系统会删除失效记录并提示下次发送建立新会话。
- **超时与取消**：握手及控制 RPC 超时 20 秒，单轮对话总超时 30 分钟；手动停止会发送 `session/cancel` 并清理适配器及其子进程树。全局并发上限为 8 个运行中的外部任务。
- **进程启动环境**：启动外部程序时会剥离所有 `HARNESS_*` 环境变量。在 Windows 上，对 `codex-acp` / `claude-agent-acp` 会自动识别全局 npm 安装包并通过 `node.exe` 直接启动脚本，不经过 `cmd.exe` 或 PowerShell。

## 边界与安全说明

- **工作目录**：外部任务固定在工作区的**第一个目录**运行。
- **上下文隔离**：不会向外部智能体转发 Java Harness 的历史对话、长期记忆、附件/文件引用或 MCP 连接器配置（`mcpServers` 传空数组）。外部智能体运行本身也不会触发长期记忆的自动提取。
- **文件修改不可撤销**：外部程序以当前系统账户权限直接读写磁盘，其改动**不经过** Java Harness 的文件修改提案与撤销队列，无法在界面中一键撤销。
- **多场景支持**：除在普通会话中通过 `/<别名>` 显式调用外，也可在**流水线岗位**（点选流程图中的 `AGENT` 节点）或**群聊成员**（群聊设置 → 成员编辑）中将执行方式切换为外部智能体。编排场景下同样要求流水线或群聊绑定包含目录的工作区；运行期间的权限确认与表单追问会在群聊时间线、流水线会话视图以及任务中心中呈现。

## 接口

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/api/external-agents` | 读取全部已配置的外部智能体（不启动进程） |
| GET | `/api/external-agents/commands` | 返回输入框 `/` 补全所需的别名与启用状态（不含本地路径与参数） |
| POST | `/api/external-agents` | 新增或按 `revision` 乐观锁更新配置 |
| POST | `/api/external-agents/test` | 执行 ACP v1 `initialize` 握手测试 |
| POST | `/api/external-agents/{id}/remove` | 按 `revision` 移除配置（保留历史运行记录） |
| GET | `/api/runs/{id}/external-interactions` | 读取某次外部运行的权限与追问记录 |
| POST | `/api/runs/{id}/external-interactions/{interactionId}` | 提交用户决定（`{ action: "accept" \| "decline", value }`） |

## 持久化

`V32__external_agents.sql` 新增三张表：

- `harness_external_agent`：外部智能体配置与乐观锁版本号；
- `harness_external_session`：本地会话、智能体与工作区指纹到远端 `sessionId` 的续接映射；
- `harness_external_interaction`：权限确认与表单追问的持久化记录；服务启动时自动将上次遗留的 `PENDING` 交互标记为 `CANCELLED`。

## 常见问题

- **提示「找不到 ACP 可执行程序」**：确认适配器已安装且所在目录在系统 `PATH` 中；若使用版本管理器（如 nvm / fnm / Volta）或自定义路径，直接填写可执行文件的绝对路径。
- **提示「外部智能体需要认证（ACP -32000）」**：在终端中先按对应适配器文档完成登录或凭据配置，再回到 Java Harness 重新发送。
- **提示「此程序不支持 ACP v1」**：确认填写的是 ACP 适配器（如 `codex-acp`、`claude-agent-acp`），而不是普通的交互式 CLI（如直接填 `codex` 或 `claude`）。
- **提示「外部智能体不会接收附件或文件引用」**：发送 `/<别名>` 前请先清空输入框中的附件和 `@` 文件引用，直接在文字中描述文件路径即可。
