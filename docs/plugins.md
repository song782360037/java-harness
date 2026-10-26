# 扩展使用与开发

本版支持本地目录/ZIP 安装、全局启用与列表启停、按需 Skills、命令、stdio 与 Streamable HTTP MCP。插件通过现有 Run 执行，使用相同的取消、截止时间、工具记录和上下文预算。

## 1. 安装示例

打开工作台「设置 → 扩展」，在「插件」页点击「添加插件」，选择本地目录或 ZIP。目录需包含 plugin.json；清单必须位于 ZIP 根目录，不能多包一层目录。

扩展中心提供「插件 / 技能 / 连接器（MCP）」三个页签，支持搜索、分类、官方精选、自定义、已安装和按名称排序。官方目录随服务离线提供，当前包含 6 个带命令的工作流插件、9 个独立技能、3 个连接器。官方标记依据平台目录的内容摘要验证，填写来源 ID 不能自行获得标记。插件内的技能和连接器显示「随插件」，管理操作作用于整个所属插件。

点击条目可阅读真实指令或工具说明。安装后默认全局启用，同时授予插件启动和 MCP 工具调用权限，不再需要配置管理、单独信任或逐次批准。列表开关可暂时停用，`…` 菜单可卸载。官方与导入包的信息只读；通过表单添加的独立技能和连接器可使用同一表单编辑。启停、编辑和卸载仅影响新任务。旧版未保存可信作者记录的条目继续只读；本版从表单创建的内容记录作者资格，导入包不会因来源标识而自动获得编辑权限。

「添加技能」填写名称、唯一标识、版本、说明和 Markdown 正文，不需要自己组装清单。唯一标识为最多 40 个小写字母/数字，可用单个短横线分段。正文最多 30 KB。保存后全局可用，同名添加会明确拒绝；编辑时保留标识，内容变更需要新版本。

「添加连接器」填写同样的基本信息和单个 MCP 服务的 JSON。支持 `stdio` 的 `command/args/env`，以及 `streamable-http` 的 `url/headers`。环境变量和 Header 值只保存在全局绑定的加密凭据中，不写入安装包；请勿将密钥放入命令参数、URL 或说明。远端 URL 需要 HTTPS，回环地址可使用 HTTP。编辑时复用添加表单。env/header 的 null 表示保留已有密钥，填写字符串替换，删除键则清除对应密钥。密钥不会回显。

官方连接器「文本工具 / JSON 工具 / 时间工具」使用宿主 JDK 启动独立 Java MCP 进程，分别提供 `analyze_text`、`format_json`、`current_time`。只处理明确传入的数据，不读取工作区或访问外网；没有虚构 GitHub、Figma 等第三方账号集成。更换 JDK 或服务 JAR 路径会生成新的运行环境版本，需要安装新的运行环境版本。损坏的安装可卸载后以相同内容重装；恢复要求该版本没有活动任务，保留历史 ID 与审批记录。

官方目录位于 `harness-server/src/main/resources/extensions/catalog.json`。发布时修改指令、元数据或内置 MCP 工具行为，**必须递增目录根字段 version**；不可用相同来源、名称和版本替换不同内容。连接器版本还附加运行环境摘要，以免移动宿主后发生版本冲突。

扩展目录 API：`GET /api/extensions` 返回官方目录与已安装条目，`GET /api/extensions/items/{id}` 返回预览，`POST /api/extensions/catalog/{id}/install` 安装官方条目。`POST /api/extensions/skills` 接收 `name/title/description/version/content/scope`；`POST /api/extensions/connectors` 用 `definition` 对象替代 `content`。二者返回 `Installation` 并全局启用，scope 字段仅用于旧客户端兼容，忽略工作区值。`GET/POST /api/extensions/installed/{id}/edit` 读取脱敏表单及更新；`POST /api/extensions/installed/{id}/enabled` 接收 enabled/revision。版本和配置变更使用 revision 防止覆盖并发修改。

纯指令示例：`examples/plugins/java-review`。安装后直接可用。在聊天输入框开头输入 `/`，从候选菜单选择 `/java-review:review`，再补充本次审查要求并发送。支持输入名称或说明筛选、↑/↓ 选择、Enter 或 Tab 填入、Esc 关闭；选择仅填入草稿，不自动发送。菜单只展示全局已启用插件的命令，首次对话使用同一全局选择。模型可调用 `load_skill` 加载正文、`read_plugin_resource` 读取包内参考资料。

Java MCP 示例需要 JDK 21，先在仓库根目录执行：

```powershell
.\mvnw.cmd -B -ntp -f examples/plugins/dependency-report/pom.xml package
```

安装目录为 `examples/plugins/dependency-report/target/plugin`。安装后全局启用，启动时自动校验程序与协议。缺少程序或配置时任务会给出原因；可在扩展列表停用或卸载。

在会话中要求汇总指定 POM 的依赖声明；模型先读取文件，再把 XML 传给 `summarize_dependencies`。此示例只解析给定 XML，不执行 Maven，也不解析继承、属性或传递依赖。工具调用使用安装时授予的权限，执行结果和状态仍记录到任务。

本项目编写的两个示例用于本地验证。外部插件启动后的本机权限由 OS 账户决定，平台的包路径校验和工具审批不构成操作系统沙箱。

## 2. 格式与兼容范围

实现的是 Agent Plugins 1.0.0 的**明确子集**，不是对所有市场包的直接兼容承诺。官方 SDK 固定为 MCP Java SDK `0.17.2`。

```text
plugin.json
skills/<skill-name>/SKILL.md
skills/<skill-name>/references/*.md
mcp.json                                  # 可选
dev.javaharness/commands/<command>.md      # 可选
dev.javaharness/config.schema.json         # 可选
server/...                                # 可选，随包交付的程序
```

最小清单：

```json
{
  "$schema": "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
  "name": "my-plugin",
  "version": "1.0.0",
  "description": "插件用途",
  "extensions": {
    "dev.javaharness": {
      "schemaVersion": 1,
      "requires": ["skills", "commands"]
    }
  }
}
```

名称使用小写字母、数字、点和短横线，版本使用 `x.y.z` 或带预发布后缀的版本。平台扩展接受 `schemaVersion`、`requires`、`commands`、`configSchema`，以及展示字段 `kind/title/category/icon`。`kind` 默认为 `plugin`；`skill` 必须只含一个技能、无命令/MCP，`connector` 必须只含一个 MCP、无技能/命令。`title/category/icon` 最多 100 字符。`commands/configSchema` 可覆盖默认位置，但必须使用 `./dev.javaharness/` 路径。`commands` 是目录，末尾 `/` 可省略；`configSchema` 是文件路径。支持的必需能力名为 `skills`、`commands`、`mcp.stdio`、`mcp.streamable-http`，未知必需能力拒绝安装。

Skill 需要 UTF-8 Markdown 和**单行** `description:` frontmatter；暂不解析完整 YAML、多行描述或脚本入口。命令为 Markdown 文本，可用 `$ARGUMENTS` 表示用户输入；命名空间为 `/插件名:文件名`。脚本不会因包含在包中而自动运行。

清单根字段限制为 `$schema/name/version/description/author/homepage/repository/license/keywords/extensions`。`.codex-plugin`、`.claude-plugin`、`.qoder-plugin` 等旧布局需先转换；市场私有 connector、Hook、Agent、UI 字段不执行。

## 3. MCP 与配置

`mcp.json` 示例：

```json
{
  "$schema": "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
  "mcpServers": {
    "report": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "${PLUGIN_ROOT}/server/report.jar"]
    }
  }
}
```

Streamable HTTP 使用 `{"type":"streamable-http","url":"https://example.com/mcp"}`。HTTP 明文仅允许本机回环；禁止重定向。支持 POST JSON/SSE 响应，不支持独立 GET 订阅、旧 HTTP+SSE、OAuth、sampling、elicitation、图片/音频、MCP Apps 或服务器驱动子任务。

stdio 的原生程序使用直接 argv 启动。`java` 解析为当前运行时的 Java；Windows 下裸命令在 PATH 中依次查找 `.exe/.com/.cmd/.bat`，支持 npm 提供的 `npx.cmd`。批处理经系统 `cmd.exe /d /v:off /s /c` 启动并逐项加引号；含引号、`%`、`!`、换行或末尾反斜杠的批处理参数会明确拒绝，请改用 `node.exe` 加脚本路径。工作目录限安装目录或该绑定的数据目录；相对目录基于安装目录。`PLUGIN_ROOT` 指不可变安装副本，`PLUGIN_DATA` 指平台分配的持久数据目录；默认只传递必要的系统环境变量。配置通过 `${config.key}` 替换，凭据只注入 env/header，不能放入命令参数。

首次导入包采用 Schema 默认配置；缺少必填配置时保留为已安装、待配置状态，可在详情补全配置与凭据后启用，不必重新导入。同源同名版本更新在配置、保留凭据及启动参数兼容时继承已有配置、凭据和启停状态，只为缺失字段补充新版默认值。不兼容时，新版保留为未选中安装，当前版本和配置继续生效；打开新版详情，按展示的 Schema 修改配置 JSON，并移除已删除服务的旧凭据，保存成功后原子切换，校验失败或 revision 冲突均保留原选择。配置接口返回的 selected 区分当前选择与新版草稿；读取草稿不修改绑定，未选中版本须先保存配置才能测试连接。安装选择失败会回滚安装记录及绑定，不留下失败操作创建的安装副本。自定义连接器仍通过添加/编辑的同一表单维护。支持 `type/properties/required/additionalProperties/items/enum/default/minimum/maximum/minLength/maxLength/minItems/maxItems/title/description/$schema`；拒绝 `$ref`、组合/联合类型、正则等未实现约束。Schema 最大递归 12 层。MCP 原始参数 Schema 会完整固定并在调用前校验，不能用 SDK 转换后丢失的字段弱化约束。

凭据键示例：`report:env:API_KEY`、`report:header:Authorization`。凭据加密保存在平台数据库，密钥文件沿用 `HARNESS_SETTINGS_KEY_FILE`；API/UI 只返回键名，Run 快照不保存值。自定义编辑通过 null 保留原值、删除键清除。普通配置会进入快照，不应填写密钥。

包配置表单支持保留、替换及删除凭据；无法解密时仍可查看列表、停用、卸载或重填全部凭据。详情中的「测试连接」可检查已保存但停用的配置，只建立连接并读取工具目录，不调用工具，也不改变启用状态。结果仅代表本次检查。保存成功但列表刷新失败时会明确提示刷新，不重复提交修改。

安装/启用授予全部插件启动与 MCP 工具权限，持久化为全局绑定的 `allowedTools: ["*"]`。路径、Schema、协议和调用边界校验继续执行。文件修改与内置命令审核沿用原有行为。

每次 MCP 调用仍记录并检查 Run、参数和工具目录快照。真正进入 `tools/call` 发送阶段前才记录 RUNNING；此前失败记录 FAILED、取消记录 CANCELLED，并显示原始原因。发送后无法确认结果记录 UNKNOWN，不自动重放。

HTTP Header 名称和值按运行时 JDK 校验，允许 `X_API_KEY` 等合法名称；协议头及 Connection、Content-Length、Expect、Host、Upgrade 等受限头在保存启用配置时拒绝。凭据不因校验失败而回显。

## 4. 作用域与生命周期

- 安装身份：来源 ID + 名称 + 版本；同身份、同内容重复安装复用原记录，同身份异内容拒绝。安装后复制文件，不继续引用源目录。
- 所有扩展统一全局生效，不再读取工作区覆盖。V12 迁移优先保留用户绑定；没有用户绑定时按 revision 降序、scope 排序选择一份工作区配置，不混合凭据。已停用保持停用；服务启动只恢复已保存的选择，未绑定的旧安装保持未选中，可在列表手动启用，卸载或解除绑定后不自动选择旧版本。旧绑定完整归档到 harness_plugin_binding_archive；它不参与启停选择，仅保留迁移选中的数据目录归属，避免已有 MCP 状态丢失。
- 程序缺失、配置失效或凭据不可读时在列表显示异常标记及原因，仍可编辑配置、停用或卸载。
- 提交 Run 时固定安装版本、配置 revision、非密钥配置、命令选择和权限；首次模型请求前固定 MCP 工具名称及原始 Schema。工具目录变化时旧 Run 调用失败，需开始新任务。
- 普通停用/升级/卸载对新任务生效；活动任务保留已捕获的版本。立即撤销会取消相关任务和等待中的调用，并关闭诊断/运行连接；已发生的外部副作用不能回滚。
- 宿主对每个 Run 创建专属 MCP 连接，结束关闭进程/连接。调用等待轮询取消与截止时间；初始化上限 15 秒、单次协议请求上限 30 秒，等待人工审批也计入 Run 总超时。
- 崩溃恢复不重放：等待审批标记中断；已记录发送意图但未确认结果标记 `UNKNOWN`。取消后迟到成功不再作为有效结果接受。未知结果需要用户检查外部系统。
- 卸载保留安装副本、业务数据和历史快照，本版无自动磁盘回收/数据清除入口。数据库及插件目录需一起备份；不要手工覆盖已安装文件。

## 5. 边界与限制

安装最多 256 个文件、512 个 ZIP 条目、单文件 8 MiB、解压总量 32 MiB；上传 ZIP 上限 16 MiB。拒绝目录符号链接、路径穿越、大小写别名和 Windows 保留文件名。每包 MCP 服务最多 8 个，单连接工具最多 128 个；每 Run 外部调用审批最多 128 次。协议帧/响应限制 1 MiB，工具输出上限 32000 字符，超限明确失败且不自动重试。

普通任务中，单个 MCP 的配置或连接初始化失败只停用本次任务中的相应连接，其余 MCP、Skill 和本地工具继续可用。异常写入 Run 快照、传给模型并在消息下方折叠提示。用户显式选择的插件命令若所属插件连接不可用，会给出错误，避免静默执行不完整工作流。包内容校验失败、取消、撤销、工具名称冲突及总工具数超限仍中止任务；工具调用阶段仍按原有审批与不可重放规则执行。当前不支持在同一包内单独停用某个 MCP 服务。MCP 本地程序可按运行用户权限访问机器，HTTPS 远端可接收调用参数及显式凭据；只启用可信插件。

本版未实现插件市场、自动更新、外部 Java JAR SPI、脚本 Hooks、子 Agent、自定义前端面板、完整 JSON Schema 或 OAuth。

## 6. 代码入口

`RunExtension` 是本轮增加的最小 SPI；`DefaultAgentEngine` 在提交阶段捕获扩展，工作线程在协调锁外打开资源。`PluginPackages` 管安装，`PluginService` 管绑定与快照，`PluginMcpClient/PluginMcpTransport` 管 SDK 和传输，`ExternalToolApprovals` 管一次性外部审批。数据库迁移为 `V11__plugins.sql` 与 `V12__global_extensions.sql`。现有命令、文件审核仍走原有业务入口。

实现与验收记录见 [验证记录](plugin-validation.md)，竞品来源和取舍见 [调研与方案](plugin-system-research-and-proposal.md)。
