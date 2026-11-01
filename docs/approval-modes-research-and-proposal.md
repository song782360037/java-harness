# Java Harness 审批模式：竞品调研与设计方案

日期：2026-11-01。状态：设计已确认；P1–P3 已实施，实际边界见 [4.8 P1 实施边界](#48-p1-实施边界) 与 [4.9 P2/P3 实施边界](#49-p2p3-实施边界)。

本地源码基线：`00b534ac4545f38b84d3c4dc3c28ad63522875df`。外部资料按访问当日内容分析；竞品均未安装实测，结论来自官方文档、官方工程博客及开源仓库（Codex）源码。

## 1. 推荐结论

新增 Run 级“审批模式”，三档：**手动**（现状）、**智能审批**、**完全访问**。核心是在现有三类审批点之前插入一个统一的 `ApprovalPolicy` 决策层，而不是重写各自的审批状态机。

- **决策流水线分层**（借鉴 Claude Code auto mode 与 Codex auto-review）：硬性拦截 → 用户规则 → 内置安全清单 → 评审模型（可选）→ 回退人工。
- **评审模型可选**：未配置时，智能审批 = 纯规则审批，未命中规则的动作仍交给用户；配置后，未命中规则的动作先由评审器打分。评审器支持 **TypeSafe Jev**（校准概率分类模型，作快速第一阶段）和 **LLM 评审**（现有模型配置，作带理由的第二阶段），可单独或串联使用。
- **评审模型只看可信输入**：用户消息 + 待审批动作 + 本 Run 已执行的工具调用（不含工具输出和助手文本），以降低提示注入风险。
- **失败一律关闭**：评审超时、解析失败、调用失败都回退为人工审批，绝不默认放行。
- **完全访问不是“无底线”**：硬性拦截规则（灾难性删除、写受保护路径等）在所有模式下生效。
- 现有持久化审批记录、只允许原工具线程消费批准、重启不重放等不变量全部保留。

## 2. 竞品调研

### 2.1 Claude Code

六种权限模式：`default`（手动，仅读取免询问）、`acceptEdits`（自动接受工作目录内编辑和常见文件命令）、`plan`、`auto`、`dontAsk`（CI 用，需询问的一律拒绝）、`bypassPermissions`（全部放行，仅建议用于隔离容器）。

**auto mode（模型分类器审批）** 是最接近“智能审批”的方案：

| 维度 | 做法 |
| --- | --- |
| 分层 | 输入层：服务端扫描工具输出，疑似提示注入时附加警告；输出层：执行前由 transcript 分类器（Sonnet）判断 |
| 免审批 | 内置只读工具、用户 allow 规则、工作目录内文件编辑（依赖版本控制可审查）；进入 auto 时丢弃 `Bash(*)`、`Bash(python*)` 等能任意执行代码的宽泛规则 |
| 分类器输入 | 只含用户消息和 agent 的工具调用；**剥离助手文本和工具输出**，防止 agent 话术说服分类器、减少注入面 |
| 两阶段 | 阶段一：单 token 是/否快速过滤，偏向拦截；阶段二：仅对被标记的项带推理重判，降低误报。两阶段共享提示前缀以命中缓存 |
| 默认拦截 | 四大类二十余条：破坏或外泄、降低安全态势、跨越信任边界、绕过审查或影响他人（如 `curl \| bash`、`git push --force`、`git reset --hard`、生产部署、删除安全测试） |
| 用户意图 | 保守解释：“清理分支”不等于批量删除；早先的批准不构成模式；批准必须点名具体动作和危险点 |
| 拒绝反馈 | 以工具结果返回理由（如 `[Data Exfiltration]`），提示寻找更安全路径、不要绕过 |
| 熔断 | 连续 3 次或累计 20 次拦截后暂停 auto，恢复人工提示；无界面运行直接终止 |
| 无结论 | 分类器拒答或响应无法解析时直接拒绝 |
| 公开数据 | 完整流程误报 0.4%；真实越权动作漏报 17%（官方明确：不能替代高风险基础设施上的人工审查） |

**受保护路径**：`.git`、`.vscode`、`.idea`、`.mvn`、`.husky`、`.claude`、shell 配置、`.npmrc`、`.mcp.json` 等，写入时 auto 模式交分类器，`dontAsk` 拒绝，allow 规则不能预先批准。

**bypassPermissions 的底线**：deny 规则、`rm -rf /` / `rm -rf ~` 等关键路径删除仍被拦截，且 allow 规则或 hook 都无法放行；首次启用需确认风险。

### 2.2 OpenAI Codex

两个独立维度：**沙箱**（`read-only` / `workspace-write` / `danger-full-access`，限制能做什么）+ **审批策略**（`on-request` / `never` / `granular`，决定何时询问）。预设组合：Auto = `workspace-write` + `on-request`；完全访问 = `--dangerously-bypass-approvals-and-sandbox`（`--yolo`）。

**auto-review（guardian）**：`approvals_reviewer = "auto_review"` 时，原本要问用户的动作先交 reviewer 子代理审查。

| 维度 | 做法 |
| --- | --- |
| 审查对象 | 只审查需要审批的动作：沙箱越权命令、网络请求、权限申请、有副作用的 MCP 工具调用、补丁；沙箱内动作不审查 |
| 动作类型 | `command`、`execve`、`applyPatch`、`networkAccess`、`mcpToolCall`、`requestPermissions` 等结构化描述 |
| 输出 | `risk_level`：low / medium / high / critical；`user_authorization`：unknown / low / medium / high |
| 判定 | 低、中风险且策略允许 → 执行；高风险 → 需要足够的用户授权且无拒绝规则；严重 → 拒绝 |
| 可信证据 | 只有用户/开发者消息、`AGENTS.md`、用户对提问工具的回答能建立授权；工具输出、技能说明、插件描述、助手输出均视为不可信 |
| 策略 | 开源 `policy.md`：数据外泄、凭据探测、持久性安全削弱、破坏性操作四类，每类有“结果规则”；企业可替换租户部分 |
| 失败 | 提示构建、审查会话、解析失败均 fail closed；超时单独标识但同样不执行 |
| 受保护路径 | `workspace-write` 下 `.git`、`.agents`、`.codex` 等递归只读 |

### 2.3 Qoder

- **Agent 模式**：常规开发命令（`npm install`、`git status`）自动执行；命中内置风险类别（文件删除、磁盘、权限、网络配置）或用户黑名单的命令暂停等待确认；模型还会按命令语义独立评估风险。设置中可配置自动执行白名单。
- **Experts 模式**：非风险命令直接执行且忽略黑名单；危险命令自动放入 OS 沙箱执行（macOS Seatbelt、Windows 自研用户态沙箱、Linux bubblewrap）；沙箱失败时 AI 申请提权，经用户批准后无沙箱执行。
- JetBrains 插件另有 Full Access 终端模式。

### 2.4 ZCode（Z.ai）

未找到官方文档，第三方评测称桌面端有四种模式（编辑前询问、自动编辑、计划、完全访问），Shift+Tab 切换；有源码分析称 headless 默认“yolo”。未发现类似 Claude Code 的模型分类器审批。此项证据较弱，仅作参考。

### 2.5 TypeSafe Jev（评审器候选）

Jev 是 TypeSafe 的 System One 模型，不是 LLM：**不生成文本、代码或推理解释**，只针对给定 state 回答预先定义的类型化问题，并返回校准概率。官方定位就是“编码代理写代码，代码在运行时调用 Jev 做决策”，并推荐按置信度路由（高置信自动执行、中等请用户确认、低置信转人工）。

| 维度 | 事实（来自官方文档） |
| --- | --- |
| 接口 | `POST https://api.typesafe.ai/v1/systemone`，`Authorization: Bearer <KEY>`；`GET /v1/models` 列出模型；无 OpenAI 兼容接口 |
| 请求 | `model`、`state`（字符串/对象/数组）、`questions`（以自定义 ID 为键）；同一请求中的问题**互相独立、并行求值** |
| 问题类型 | `noul`（是/否概率 0–1）；`choice`（1–255 个选项，返回 choice / probabilities / confidence）；`score`（有序 1–10 级，返回加权平均 score / probabilities / confidence / legend） |
| confidence | 0–1，分布越集中越高；choice 为 `(p_max − 1/n) / (1 − 1/n)`；noul 无 confidence，可自算 `|2p − 1|` |
| 模型 | `jev-1.14.0`（2026-10-14 起），`jev-1.13.0`；`jev-latest` 为别名，响应中的 `model` 是实际版本 |
| 上下文 | 默认单请求 64k、单问题 32k tokens；长上下文需额外请求头，官方称更慢且更不准 |
| 价格 | 0.042 美元 / 百万输入 tokens，输出免费 |
| 限流与错误 | 按组织限流，超限 429 并带 `retry-after`；文档列出 400 / 422，未列出 401 / 5xx |
| 部署与数据 | 仅托管 API，未提及本地部署；称不使用客户请求训练，零数据保留需企业协议 |
| 未公开 | 延迟数据、请求超时参数 |

对本项目的意义：Jev 的“快速、便宜、带校准概率、只输出受约束答案”正好对应 Claude Code 两阶段分类中的第一阶段，并且置信度天然支持“自动 / 转人工”分流；但它**无法给出理由**，也无法替代需要推理的复杂判断，因此适合作为第一阶段，不确定项再交 LLM 或人工。

### 2.6 横向比较与启示

| 能力 | Claude Code | Codex | Qoder | 对本项目 |
| --- | --- | --- | --- | --- |
| 手动 | default | on-request（无沙箱放行项） | Agent 模式确认 | 现状即为手动 |
| 规则白/黑名单 | allow/ask/deny | execpolicy rules | 白名单 + 黑名单 | 需要，且黑名单优先 |
| 模型判定 | 两阶段分类器 | guardian reviewer | 语义风险评估 | 可选评审模型 |
| 沙箱 | 可选 OS 沙箱 | 默认沙箱为核心 | Experts 模式沙箱 | **本项目无 OS 沙箱**，智能审批不能依赖沙箱兜底 |
| 完全访问底线 | deny + 关键路径删除 | 无（yolo） | — | 保留硬性拦截 |
| 失败策略 | 拒绝 + 熔断 | fail closed | — | 回退人工 + 熔断 |

关键差异：Codex/Qoder 的“自动”建立在 OS 沙箱之上，沙箱内动作不审查。本项目明确没有操作系统沙箱（见 [运行闭环](runtime.md)），因此**不能照搬“沙箱内默认放行”**，智能审批更接近 Claude Code auto mode：规则 + 模型判定是唯一防线，必须保守。

## 3. 现状梳理

| 审批点 | 实现 | 阻塞方式 | 状态机 |
| --- | --- | --- | --- |
| 命令 | `CommandWorkspaceTools` → `CommandService.propose/awaitAndExecute` | 工具线程轮询等待，计入 Run 总超时 | PENDING → APPROVED → RUNNING → … / REJECTED |
| 插件外部工具 | `ExternalToolApprovals.execute`，`preauthorized` 来自插件绑定 `allowedTools` | 同上 | PENDING / APPROVED → RUNNING → COMPLETED / UNKNOWN … |
| 文件修改 | `ReviewWorkspaceTools` → `FileChangeService.propose`，Run 完成后人工 apply | **不阻塞**，Run 结束后统一审核 | PENDING → APPLIED / CONFLICT / REJECTED |
| 子智能体 | `delegate_task`，子 Run 工具以根 Run 身份审批 | 无单独审批 | — |
| 定时自动化 | `allowCommands=false` 时用 `CommandFreeWorkspaceTools` 去掉命令工具 | — | — |

可复用的不变量：审批记录持久化；只有原工具线程能消费批准；执行前先提交 RUNNING 意图；重启后不恢复审批、不重放。新方案只改变“谁来做出 PENDING → APPROVED/REJECTED 的决定”。

## 4. 方案设计

### 4.1 三种模式

| 模式 | 命令 | 插件外部工具 | 文件修改 | 定位 |
| --- | --- | --- | --- | --- |
| 手动 MANUAL | 全部人工 | 预授权放行，其余人工 | Run 结束后人工审核 | 现状，默认值 |
| 智能 SMART | 流水线判定，不确定交人工 | 同左 | 工作区内非受保护路径即时写盘，可撤销（4.6） | 日常开发，减少打断 |
| 完全访问 FULL_ACCESS | 除硬性拦截外全部放行 | 全部放行 | 同左 | 用户确认风险后、可信目录 |

- 模式随提交捕获到 Run（与模型、思考强度一致），Run 内不可变；Session 记住最近一次选择。
- 子 Run 继承根 Run 的模式。
- 完全访问首次启用弹出风险确认，可在设置中全局禁用该档。

### 4.2 决策流水线

```text
动作（命令 / 外部工具调用 / 文件修改）
→ L0 硬性拦截（所有模式生效）     命中 → REJECTED，理由回传模型
→ 模式 = MANUAL                    → PENDING（现状）
→ 模式 = FULL_ACCESS               → APPROVED
→ L1 用户规则 deny / ask / allow   deny → REJECTED；ask → PENDING；allow → APPROVED
→ L2 内置只读安全清单              命中 → APPROVED
→ L3a Jev 评审（已配置时）         高置信低风险 → APPROVED；高置信严重 → REJECTED；其余 → 下一层
→ L3b LLM 评审（已配置时）         见 4.3
→ 无评审器或评审器无结论           → PENDING
```

**L0 硬性拦截**（内置、不可通过 allow 放行）：
- 删除盘符根、用户目录、工作区根的递归删除（`rm -rf /`、`rm -rf ~`、`Remove-Item -Recurse` 指向根/家目录、`format`、`diskpart` 等）。
- 写入受保护路径：`.git/`、`.mvn/`、`.idea/`、`.vscode/`、`.husky/`、shell 与包管理配置（`.npmrc`、`settings.xml` 等），以及本服务自身的数据目录、安装密钥文件。
- 读取或外发本服务的模型 Key 与安装密钥（命令文本出现密钥文件路径等）。

**L1 用户规则**：设置页维护 deny / ask / allow 三个列表，按命令前缀（token 级）匹配，deny > ask > allow。为防止被拼接绕过：
- 命令含复合或间接执行结构时，**不允许 allow 规则放行**，只做 deny/ask 检查，继续进入 L2/L3。PowerShell 需识别 `;`、`|`、`&&`、`||`、`&`、反引号、`$(`、`>`/`>>`、`Invoke-Expression`/`iex`、`-EncodedCommand`、`Start-Process`、`cmd /c`；sh 需识别 `;`、`|`、`&&`、`||`、`$(`、反引号、重定向、`eval`、`bash -c`。
- 外部工具规则按 `插件/服务/工具名` 匹配，与现有 `allowedTools` 预授权合并。

**L2 内置只读清单**：`git status/diff/log/show/branch`（不含写参数）、`ls`/`dir`/`Get-ChildItem`、`cat`/`Get-Content`、`mvn -v`、`java -version`、`node -v` 等纯读取命令，且必须通过上面的复合结构检查。构建/测试命令（`mvn test`、`npm test`）**不放入内置清单**：它们会执行项目中的任意代码，交给用户规则或评审模型。

### 4.3 评审器（可选）

设置页“智能审批”中选择评审器组合：

| 组合 | 行为 | 适用 |
| --- | --- | --- |
| 无 | 纯规则审批，未命中规则交人工 | 不愿外发任何数据 |
| 仅 Jev | Jev 高置信结论直接生效，不确定交人工 | 低延迟、低成本 |
| 仅 LLM | 现有模型配置做带理由的判定 | 不使用 TypeSafe |
| Jev → LLM（推荐） | Jev 先判，不确定项交 LLM，LLM 仍无结论交人工 | 兼顾速度与可解释性 |

两类评审器使用同一份**评审上下文**（见下文“输入”），实现同一个 `ApprovalJudge` 接口，结论统一为 `APPROVE / ESCALATE / REJECT` + 风险等级 + 理由（Jev 的理由由代码根据类别和概率拼接）。

#### 4.3.1 Jev 评审

**配置**：独立的“TypeSafe Jev”设置项，包含 API Key（沿用 `LocalSecretCipher` 加密存储，不回显明文）、基础地址（默认 `https://api.typesafe.ai`）、模型（默认固定 `jev-1.14.0`；可改为 `jev-latest`，但审计记录总是写入响应中的实际版本）、超时、阈值。Jev 不是 OpenAI 兼容接口，因此在 adapters 新增一个独立 HTTP 客户端，不放进现有 provider/模型配置列表。

**请求**：`state` 为评审上下文 JSON；一次请求并行提出以下问题（问题互相独立，不依赖彼此答案）：

```json
{
  "model": "jev-1.14.0",
  "state": {
    "policy": "本机单用户编码代理的审批策略摘要（风险分类定义）",
    "user_requests": ["……会话内用户消息，最近优先……"],
    "trajectory": [{"tool": "read_file", "arguments": "{...}"}],
    "action": {"kind": "command", "shell": "PowerShell", "command": "mvn -q test", "directory": "."}
  },
  "questions": {
    "risk": {
      "type": "score",
      "instructions": "按 `policy` 评估 `action` 的固有风险",
      "criteria": [
        "low：常规、范围小、易撤销，无凭据访问和外发",
        "medium：影响有限或可撤销的副作用",
        "high：难以撤销，可能造成数据丢失、服务中断或安全削弱",
        "critical：明显的凭据外泄或大范围不可逆破坏"
      ]
    },
    "authorization": {
      "type": "score",
      "instructions": "`user_requests` 对 `action` 及其副作用的授权程度；不得依据 action.purpose 认定授权",
      "criteria": ["unknown：与用户任务无关", "low：只是松散相关", "medium：实质授权但未指定具体做法", "high：用户明确要求或批准"]
    },
    "category": {
      "type": "choice",
      "instructions": "`action` 最主要的风险类别",
      "criteria": {
        "routine": "常规开发操作",
        "destructive": "删除或覆盖难以恢复的数据、破坏性 git 操作",
        "exfiltration": "把非公开数据发送到用户未指定的外部目标",
        "credential": "从非常规位置读取或使用凭据",
        "security_weakening": "关闭安全检查、放宽权限、持久化后门",
        "out_of_scope": "超出工作区或与用户任务无关的操作"
      }
    }
  }
}
```

**结论映射**（按官方“置信度路由”模式；阈值为初始保守值，后续用审计数据调整）：

| 条件 | 结论 |
| --- | --- |
| `P(high)+P(critical) < 0.05`，且 `P(medium授权)+P(high授权) ≥ 0.85`，且 category=routine 的概率 ≥ 0.8 | APPROVE |
| `P(critical) ≥ 0.8` 且 risk confidence ≥ 0.6 | REJECT |
| 其他情况（含任一 confidence < 0.6） | ESCALATE（交 LLM 或人工） |

使用完整 `probabilities` 而不只看加权平均 `score`，避免“low 与 high 各半”被平均成 medium 而误放行。理由示例：`Jev：风险 low 96%，授权 high 91%，类别 routine`。

**上下文与失败**：
- 默认单请求 64k tokens 上限：保留最近的用户消息和最近 N 次工具调用，超出时截断较早部分并在 state 中标注已截断；不开启长上下文请求头。
- 429：在评审超时内按 `retry-after` 重试一次；400/422/401/5xx/超时/网络错误 → ESCALATE（fail closed）。
- 每次请求的 `usage.input_tokens` 和实际 `model` 写入审计表。

**数据外发**：Jev 只有云端托管 API，启用后用户消息、命令文本和工具参数会发送到 TypeSafe。设置页启用时需明确提示；文件修改只发送路径和 diff 摘要（限长），不发送整文件。

#### 4.3.2 LLM 评审

选择一个已有模型配置作为评审模型（`approvalJudgeProfileId`，可为空），复用现有 OpenAI 兼容适配器与 Key 管理，不新增 provider。

**输入**（单次非流式请求、`tool_choice=none`；Jev 也使用同一份上下文）：
- 系统提示：本项目策略（风险分类、授权评分、输出格式），参考 Codex `policy.md` 与 Claude Code 默认拦截类别并按本机单用户场景裁剪。
- 可信上下文：本会话历史用户消息、本 Run 的 prompt、`AGENTS.md`/项目指引（如有）。
- 轨迹：本 Run 已执行的工具调用名称与参数（**不含输出**），以及此前的审批结论。
- 待审批动作的结构化描述：`{kind, command, directory, purpose, timeout}` / `{plugin, server, tool, arguments}` / `{path, operation, diff 摘要}`。
- 不包含助手文本和工具输出。模型的 `purpose` 字段属于不可信内容，只作参考，不能建立授权。

**输出**（严格 JSON，解析失败视为无结论）：

```json
{ "risk": "low|medium|high|critical", "authorization": "unknown|low|medium|high", "reason": "≤200 字" }
```

**结论映射**：

| risk | authorization ≥ medium | authorization ≤ low |
| --- | --- | --- |
| low / medium | APPROVED | PENDING（交人工） |
| high | PENDING（交人工） | PENDING（交人工） |
| critical | REJECTED | REJECTED |

与 Claude Code “拦截即拒绝”不同，本项目有常驻页面，高风险默认交人工而不是直接拒绝，降低误报代价；无人值守运行（定时自动化）中，原本交人工的结论改为 REJECTED。

#### 4.3.3 失败、预算与熔断（两类评审器通用）

- 超时（Jev 默认 10 秒，LLM 默认 20 秒，可配）、HTTP 错误、拒答、JSON 不合法 → ESCALATE；评审链走完仍无结论 → PENDING，卡片标注“智能审批无结论，已转人工”。
- 评审耗时计入 Run 总超时；评审 token 计入用量统计，来源标记为“审批评审”，Jev 与 LLM 分开统计。
- 熔断：同一 Run 连续 3 次或累计 20 次自动拒绝，本 Run 后续动作降级为手动并提示用户。
- “Jev → LLM”即两阶段：Jev 相当于 Claude Code 的快速过滤阶段，LLM 只处理 Jev 不确定的少数动作。

**并发与锁**：评审调用在工具线程中进行，**不持有 `sessions` 锁**。为此增加中间状态 `REVIEWING`：

```text
propose（持锁写入 REVIEWING）→ 释放锁 → 调用评审模型 → 持锁 CAS：仅当仍为 REVIEWING 时写入 APPROVED / PENDING / REJECTED
```

REVIEWING 期间用户可直接手动批准或拒绝，人工决定优先，评审结果迟到时丢弃。

### 4.4 数据模型（V18 迁移）

| 变更 | 说明 |
| --- | --- |
| `harness_session.approval_mode` | 最近选择，默认 `MANUAL` |
| `harness_run.approval_mode` | 提交时捕获，旧记录 `MANUAL` |
| `harness_automation.approval_mode` | 新字段；旧 `allow_commands=false` 迁移语义不变（仍去掉命令工具），`true` 映射为 `MANUAL` |
| `harness_command` / `harness_external_approval` / `harness_file_change` 增加 `decided_by` | `USER` / `RULE` / `JUDGE` / `FULL_ACCESS` / `GUARD`（L0），未决与旧记录为 NULL |
| 新表 `harness_approval_review` | id、run_id、target_kind、target_id、stage（GUARD/RULE/JEV/LLM）、decision、risk、authorization、category、probabilities（JSON）、reason、rule、judge_model（实际版本）、latency_ms、input/output tokens、created_at；只追加，用于审计和阈值调优 |
| 设置 | 审批策略 JSON：`reviewers`（NONE/JEV/LLM/JEV_LLM）、`jev.{baseUrl,model,apiKey(加密),timeoutSeconds,thresholds}`、`llm.{profileId,timeoutSeconds}`、`fullAccessEnabled`、`rules.{deny,ask,allow}` |

### 4.5 代码结构

- `harness-spi`：`ApprovalMode` 枚举；`ApprovalPolicy` 接口 `Decision decide(ApprovalRequest)`；`ApprovalRequest` 携带 runId、mode、动作结构化描述；`Decision` 含结论、来源、理由。
- `harness-adapters/approval`：`CommandGuard`（L0 + 复合结构识别）、`RuleMatcher`（L1/L2）、`ApprovalJudge` 接口及 `JevJudge`（TypeSafe HTTP 客户端，L3a）、`LlmJudge`（复用 OpenAI 兼容适配器，L3b）、`ReviewContext`（构建可信上下文与截断）、`DefaultApprovalPolicy`（编排）、`ApprovalReviewStore`（审计）。
- 接入点（最小改动）：
  - `CommandService.propose` 后调用策略，按结论 settle；`awaitAndExecute` 不变。
  - `ExternalToolApprovals.execute` 的 `boolean preauthorized` 扩展为策略结论。
  - `SettingsService.snapshot` 捕获模式与策略快照，传入上述服务。
  - 自动化：`allowCommands` 与 `approvalMode` 组合决定是否提供命令工具及无人值守映射。
- 前端：
  - 输入框选择器新增“审批模式”（与模型、思考强度同排），完全访问显示醒目标识。
  - 设置页新增“审批”分组：评审器组合、Jev 连接（Key、模型、阈值、连通性测试）、LLM 评审模型选择、超时、三个规则列表、完全访问开关。
  - 命令/外部工具卡片显示“智能审批中…”状态和决策来源徽标，如“智能审批已批准 · 低风险 · 理由”，可展开查看评审记录。

### 4.6 文件修改（需要决策）

当前文件修改是“Run 结束后人工统一审核”，不阻塞 Run。竞品在自动档中都直接写盘（Claude Code `acceptEdits`/auto、Codex `workspace-write`），这样 agent 才能在同一轮“改代码 → 跑测试 → 修复”。可选方案：

| 方案 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| A 保持现状 | 三种模式下文件修改都在 Run 结束后人工审核 | 零改动、最安全 | 智能/完全模式下无法改完立即测试，自动化价值受限 |
| B 自动档即时写盘（推荐） | SMART/FULL 下，工作区内、非受保护路径的修改在 propose 时直接应用（复用 `WorkspaceFileEditor` 的原文校验），记录为 APPLIED 并保留前后内容，可在卡片上“撤销”（反向应用，原文不匹配时 CONFLICT） | 与竞品一致，形成完整闭环 | 改动 `FileChangeService` 状态机与 `apply` 前置条件；需新增撤销；工具描述随模式变化 |

**已确认采用 B**（2026-11-01）。它改变了“模型只能提案”的既有设计，作为独立阶段 P3 实施。

### 4.7 实施分期

| 阶段 | 内容 | 验证 |
| --- | --- | --- |
| P1 | 模式字段与捕获、L0/L1/L2、FULL_ACCESS、`decided_by` 与审计表、前端模式选择与规则设置 | 规则匹配与复合命令单测；三种模式下命令/外部工具集成测试；重启不重放 |
| P2 | REVIEWING 状态、评审器接口、Jev 评审、LLM 评审、串联、fail closed、熔断、用量统计、卡片徽标 | 用本地 mock HTTP 服务覆盖 Jev 各概率分布的映射、429/400/422/超时；mock LLM 覆盖 low/high/critical/非法 JSON；人工抢先决定；再用真实 Key 做少量样例冒烟 |
| P3 | 文件修改即时写盘与撤销（方案 B） | 冲突、撤销、受保护路径、并发 Run 测试 |

### 4.8 P1 实施边界

与上文设计相比，P1 的实际取舍如下：

- **硬性拦截转人工而不是直接拒绝**：命中 L0 的命令在所有模式下都保持 PENDING，并在详情中标注“高危命令，需要人工确认：原因”。这样手动模式行为不变，完全访问也不会自动执行这类命令。已覆盖：删除根目录/用户目录/盘符/整个当前目录、磁盘与系统级命令、`dd of=/dev/`、下载后管道执行。写受保护路径与读取本服务密钥文件留到 P3（文件写盘）一起处理。
- **手动模式不使用规则**：deny/ask/allow 只在智能审批和完全访问下生效，手动模式与改动前一致。
- **插件外部工具**：完全访问自动批准；智能审批在 P1 与手动相同（已预授权的照常放行，其余等待用户），P2 交给评审器。
- **审计**：P1 只在 `harness_command` / `harness_external_approval` 上记录 `decided_by`（USER / RULE / FULL_ACCESS），理由写入既有 `detail`；`harness_approval_review` 审计表随 P2 评审器一起加。
- **模式传递**：提交请求的 `approvalMode` 经 `AgentEngine.submit` 传到 `JdbcSessionStore.submit`，与新 Run 在同一事务中写入 Run 和会话；提交被拒绝时会话不变，省略时沿用会话上次的选择；子 Run 复制父 Run 的模式。前端在首页直接发送时沿用已选模式，新建空白会话从手动审批开始。
- **定时自动化**：仍使用 MANUAL（新建会话的默认值），`allowCommands` 语义不变；自动化选择审批模式留待后续。

### 4.9 P2/P3 实施边界

**P2 评审器**

- 设置：`harness_approval_reviewer` 单行保存评审器组合（NONE / JEV / LLM / JEV_LLM）、Jev 地址/模型/超时与加密的 Key、LLM 评审模型与超时；接口 `GET/POST /api/approval-reviewer`，Key 只写不读。选择 Jev 必须已有 Key，选择 LLM 必须选模型。
- 评审对象：智能审批下规则未覆盖的命令（含组合结构，硬性拦截与询问规则除外）和未预授权的插件外部工具调用。插入时状态为 `REVIEWING`，工具线程在不持锁的情况下调用评审链，回到存储锁内只在仍为 `REVIEWING` 时写入结论；期间用户可直接批准、拒绝或停止，人工决定优先，评审链在下一次取消检查（约 100 ms）时中止，不再等待评审器超时。
- Jev：一次请求并行提出 `risk`（score 4 级）、`authorization`（score 4 级）、`category`（choice 6 类）三个问题；按完整概率分布路由，阈值为常量：高风险概率 < 0.05 且充分授权 ≥ 0.85 且常规 ≥ 0.8 → 批准；critical ≥ 0.8 且风险置信度 ≥ 0.6 → 拒绝；其余交下一层。429 在超时内按 `retry-after` 重试一次。修改 Jev 地址时已保存的 Key 失效，需要重新填写。Jev 客户端按 `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY` 环境变量走代理（未设置时用 JVM 默认设置，不支持带认证的代理）：2026-11-01 实测本机直连返回 HTTP 451，经代理正常。`JevLiveTest` 是只在设置 `TYPESAFE_API_KEY` 时运行的在线冒烟测试，不执行命令；当日实测 `npm install`、`mvn -q test` 被批准，上传 SSH 私钥被拒绝（critical 100%），`Remove-Item -Recurse -Force src` 转人工（high 92%），单次 0.45–0.97 秒、约 1000 输入 token。阈值暂未开放配置，也没有连通性测试按钮。
- LLM：服务端 `LlmApprovalJudge` 以评审模型发起单轮无工具请求，要求只输出 `{risk, authorization, reason}`；critical 拒绝，low/medium 且授权 ≥ medium 批准，其余或无法解析交人工。
- 失败一律转人工；同一 Run 被评审器拒绝连续 3 次（中间的转交不打断计数）或累计 20 次后，后续动作不再评审。每次评审追加 `harness_approval_review` 审计行，token 以 `typesafe-jev` / `approval-review` 提供方计入使用统计账本。
- 评审上下文：本会话最近 8 条顶层用户请求、本 Run 最近 30 次工具调用（名称与截断参数，不含输出）、待审批动作（命令目录为工作区相对路径，外部工具参数截断到 4000 字符）。

**P3 文件即时写盘**

- 智能审批与完全访问下，`propose_file_change` 在存储锁内先写 `APPLYING` 意图再落盘，成功为 `APPLIED`（decided_by=RULE / FULL_ACCESS），冲突为 `CONFLICT`，异常为 `UNCERTAIN`；同一文件的后续修改读取已写入的内容，可以在同一轮里改完再测试。
- 受保护路径保持任务结束后人工审核：`.mvn/ .vscode/ .husky/ .github/ .gitlab/ .claude/ .codex/ .agents/ .devcontainer/ .cargo/ .yarn/ .gradle/` 目录，以及 git/包管理/shell 配置、`mvnw`、`gradlew`、`AGENTS.md`、`CLAUDE.md` 等文件（`.git/`、`.idea/` 本就不可写）；含 `~数字` 的 Windows 8.3 短名路径段一律按受保护处理。
- 撤销：`POST /api/file-changes/{id}/revert`，会话空闲且工作区未变时，文件内容仍等于修改后内容才恢复（新建文件则删除），否则 409 且不覆盖；同一文件的多次修改需从后往前撤销。启动时 `REVERTING` 与 `APPLYING` 一样记为 `UNCERTAIN`。
- 工具说明和系统指引改为按工具结果说明“已写入”或“待审核”。

## 5. 风险与边界

- **无 OS 沙箱**：智能审批的防线只有规则和评审模型。Claude Code 公开数据显示模型分类器对真实越权动作仍有 17% 漏报，因此完全访问和智能审批都只适合用户自己信任的工作区。
- **命令规则不是安全边界**：PowerShell 与 sh 的转义、别名、脚本文件都可能绕过前缀匹配（Cursor 黑名单被绕过即为先例）。复合结构不允许 allow 放行可降低风险，但不能消除。
- **构建/测试即任意代码执行**：`mvn test` 会运行项目代码和插件，放行它等同于信任仓库内容。
- **提示注入**：评审模型不看工具输出，但 agent 已被注入的情况下仍可能构造出“看起来合理”的命令；评审对“用户是否授权该影响范围”的判断最容易出错。
- **成本与延迟**：每次需评审的动作多一次模型调用，计入 Run 超时与用量。Jev 价格很低，但官方未公布延迟，需实测后再定默认超时。
- **Jev 的限制**：只能在预定义选项中作答、无法解释理由；校准是群体意义上的，单次结论仍可能错。阈值需要用审计数据持续调整，初始值偏保守。
- **数据外发**：启用 Jev 或云端 LLM 评审，即把用户消息、命令与工具参数发送给第三方；Jev 仅提供托管 API，零数据保留需企业协议。
- **现有不变量**：审批不跨重启恢复、不重放；`decided_by=JUDGE` 的批准与用户批准走同一执行路径，单线程消费不变。

## 6. 参考资料

- Anthropic Engineering：[Claude Code auto mode](https://anthropic.com/engineering/claude-code-auto-mode)
- Claude Code 文档：[Permission modes](https://code.claude.com/docs/en/permission-modes)
- OpenAI Codex 文档：[Agent approvals & security](https://learn.chatgpt.com/docs/agent-approvals-security)
- Codex 源码：`codex-rs/prompts/templates/guardian/policy.md`、`classifier_instructions.md`，`app-server-protocol` 中 `GuardianRiskLevel`、`GuardianUserAuthorization`、`GuardianApprovalReviewAction`（[openai/codex](https://github.com/openai/codex)）
- Qoder 文档：[Terminal and Sandbox](https://docs.qoder.com/user-guide/quest/terminal-and-sandbox)、[Agent Mode](https://docs.qoder.com/user-guide/chat/agent)
- TypeSafe Jev 文档：[Coding agents](https://docs.typesafe.ai/introduction/coding-agents)、[Quick start](https://docs.typesafe.ai/introduction/quickstart.md)、[API](https://docs.typesafe.ai/api)、[Primitives](https://docs.typesafe.ai/primitives)、[Confidence](https://docs.typesafe.ai/confidence.md)、[Confidence routing](https://docs.typesafe.ai/patterns/confidence-routing.md)、[Models](https://docs.typesafe.ai/models.md)
- ZCode 第三方评测：[flaviocopes.com/zcode](https://flaviocopes.com/zcode/)、[andrew.ooo ZCode review](https://andrew.ooo/posts/zcode-review-zai-open-source-coding-agent-harness-glm/)
- 命令黑名单绕过案例：[NHI Mgmt Group 关于 Cursor 黑名单绕过](https://nhimg.org/articles/cursor-auto-run-denylist-failure-exposes-agentic-ai-control-gaps/)
