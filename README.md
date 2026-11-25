<div align="center">

<img src="harness-web/public/harness-icon.svg" alt="Java Harness Logo" width="76" height="76" />

# Java Harness

**用 Java 21 构建的本地优先 AI 编程工作台**

连接你选择的模型与项目工作区，将多角色像素协作工坊、泳道流水线、长期记忆图谱、<br>
定时自动化与 MCP / ACP 扩展生态汇聚于一处，所有数据留在你本机。

<p>
  <a href="https://github.com/song782360037/java-harness/actions/workflows/build.yml"><img src="https://github.com/song782360037/java-harness/actions/workflows/build.yml/badge.svg" alt="Build" /></a>
  <a href="https://github.com/song782360037/java-harness/releases"><img src="https://img.shields.io/github/v/release/song782360037/java-harness?display_name=tag&sort=semver&color=2d6a4d" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License" /></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5" />
  <img src="https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white" alt="Vue 3" />
  <img src="https://img.shields.io/badge/Electron-Desktop-47848F?logo=electron&logoColor=white" alt="Electron" />
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey" alt="Platform" />
</p>

<p>
  <a href="#演示视频">演示视频</a> ·
  <a href="#核心亮点">核心亮点</a> ·
  <a href="#界面预览">界面预览</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#插件与扩展">插件与扩展</a> ·
  <a href="#系统架构">系统架构</a> ·
  <a href="#文档导航">文档导航</a> ·
  <a href="#参与贡献">参与贡献</a>
</p>

</div>

---

## 演示视频

https://github.com/user-attachments/assets/a317988a-7835-4c3c-b035-16fcd6ec3453

## 核心亮点

Java Harness 是一个运行在本机的开源 AI 编程工作台，提供 **Windows / macOS / Linux 跨平台桌面客户端**（内置精简 JRE，无需预装 JDK），也可作为单个可运行 JAR 在任意 JDK 21 环境中启动。

- **多模型自由切换**：接入任意兼容 OpenAI Chat Completions 接口的服务或本地模型网关，在对话中随时热切换模型与思考强度。
- **像素协作工坊与多智能体**：支持并行子智能体（`explore` / `general`）在独立上下文中完成探索与实现；也可建立多角色 AI 群聊，在可视化像素协作办公室中由隐藏主持人拆解依赖任务、互相 `@` 接力推进。
- **人机泳道流水线与定时自动化**：按需求生成固定研发流水线，AI 岗位自动流转、关键节点在「你」泳道人工确认与打回闭环；支持按每天 / 工作日 / 每周 / 每小时 / Cron 定时自动新建会话执行巡检。
- **可追溯长期记忆图谱**：从历史对话中自动沉淀架构约定、工程偏好与排障经验，以主题知识图谱呈现，支持跨会话召回、来源追溯与人工纠偏。
- **开放扩展生态**：统一管理插件包、Markdown 技能、MCP 连接器（`stdio` 与 Streamable HTTP）以及外部 ACP 智能体（`/claude`、`/codex`）。
- **纯 Java 21 内核 · 本机优先**：`harness-core` 与 `harness-spi` 零框架依赖；服务仅监听 `127.0.0.1`，会话、记忆与加密凭据保存在本机 H2 数据库，文件修改与命令执行均可控可回溯。

## 界面预览

<table>
  <tr>
    <td align="center" width="33.3%">
      <img src="docs/assets/preview-studio.jpg" alt="像素协作工坊" />
      <br />
      <sub><strong>像素协作工坊</strong> · 多角色 AI 团队实时协同</sub>
    </td>
    <td align="center" width="33.3%">
      <img src="docs/assets/preview-flows.jpg" alt="人机泳道流水线" />
      <br />
      <sub><strong>固定泳道流水线</strong> · AI 自动流转与关键节点把关</sub>
    </td>
    <td align="center" width="33.3%">
      <img src="docs/assets/preview-memory.jpg" alt="长期记忆主题图谱" />
      <br />
      <sub><strong>长期记忆图谱</strong> · 主题聚类、显式关联与来源追溯</sub>
    </td>
  </tr>
</table>

## 功能一览

| 能力 | 说明 |
| --- | --- |
| **多模型对话** | 管理多个模型提供商，在聊天中切换模型与思考强度；支持流式输出、Markdown、代码块与表格。 |
| **项目工作区** | 为项目配置名称、图标与颜色，关联一个或多个本地目录，在多个会话间复用上下文。 |
| **代码探索** | 浏览目录、读取文件、全文搜索、查看 Git 状态与差异，基于真实源码分析与定位问题。 |
| **子智能体** | 将相互独立的子任务并行委派给只读探索（`explore`）或实现（`general`）子智能体，独立上下文完成后仅带回精炼结论。 |
| **群聊与像素工坊** | 内置项目经理、设计、前后端、测试、运维等角色模板，支持讨论模式与任务编排模式，可视化追踪每位成员状态。 |
| **固定流水线** | 按需求生成可复用的岗位流转图，串联触发器、AI 岗位与人工把关节点，支持打回补充闭环。 |
| **定时自动化** | 配置提示词、工作区、模型与频率（每天 / 工作日 / 每周 / 每小时 / Cron / 手动），到点自动创建会话运行。 |
| **长期记忆图谱** | 开启后自动从对话提炼记忆，在主题图谱中查看、纠正、建立关系并追溯原始出处。 |
| **提示词优化** | 结合最近对话、文件引用、附件与相关记忆润色输入草稿，回填后由你决定是否发送。 |
| **修改审核与审批** | 文件修改以 Diff 呈现并支持撤销；提供手动 / 智能 / 完全访问三档审批与 deny / ask / allow 命令规则。 |
| **任务中心与记录** | 跨会话查看运行中与待处理任务，桌面端支持系统通知与托盘常驻；完整记录每次调用的输入快照与工具结果。 |
| **明暗主题与偏好** | 原生支持明亮 / 黑暗 / 跟随系统主题，可调字号、发送快捷键与消息跟随策略。 |

## 快速开始

### 方式一：下载桌面客户端（推荐）

前往 [Releases](https://github.com/song782360037/java-harness/releases) 选择对应平台安装包（均内置精简 JRE，无需预装 JDK）：

- **Windows (x64)**：`Java-Harness-Setup-<版本>-x64.exe`（支持应用内自动更新，数据保存在 `%APPDATA%\Java Harness\`）
- **macOS (Apple Silicon / Intel)**：`Java-Harness-<版本>-mac-arm64.dmg` 或 `Java-Harness-<版本>-mac-x64.dmg`（数据保存在 `~/Library/Application Support/Java Harness/`）
- **Linux (x64)**：`Java-Harness-<版本>-linux-x64.AppImage`（支持应用内自动更新）或 `Java-Harness-<版本>-linux-x64.deb`（数据保存在 `~/.config/Java Harness/`）

> [!NOTE]
> 安装包若未做代码签名：Windows 首次运行 SmartScreen 选择「仍要运行」；macOS 拖入应用程序后若提示无法验证，可在终端执行 `xattr -rd com.apple.quarantine "/Applications/Java Harness.app"`。

### 方式二：从源码构建运行

**环境要求**

- JDK 21（配置 `JAVA_HOME`）
- Node.js 22.12+（推荐 24 LTS）与 npm
- 无需单独安装 Maven（仓库自带 Maven Wrapper）

**构建与启动**

```bash
git clone https://github.com/song782360037/java-harness.git
cd java-harness

# 1. 构建前端工作台
npm --prefix harness-web ci
npm --prefix harness-web run build

# 2. 打包包含前端资源的后端 JAR（Windows 下使用 .\mvnw.cmd）
sh ./mvnw -B -ntp -Pweb clean verify

# 3. 启动本地服务
java -jar harness-server/target/harness-server-0.3.2.jar
```

启动后在浏览器访问 <http://127.0.0.1:8080/>，数据默认写入启动目录下的 `data/`。

### 首次配置

1. 打开 **设置 → 模型与提供商**，添加模型提供商（API 地址与 Key）并配置模型。
2. 点击 **新的任务**，在输入框底部选择项目工作区、模型与思考强度。
3. 开始探索项目、协作开发，或在左侧栏进入 **群聊**、**流水线**、**记忆** 与 **自动化**。

开发模式（前后端分别热启动）与端口配置说明见 [使用与开发参考](docs/usage-and-development.md)。

## 插件与扩展

在 **设置 → 扩展** 中统一发现、安装与管理插件、技能、MCP 连接器和外部智能体：

| 扩展类型 | 用途 | 接入方式 |
| --- | --- | --- |
| **插件（Plugins）** | 将技能、命令与工具打包为可复用的团队工作流（如代码审查、架构理解、发布检查）。 | 从本地目录或 ZIP 压缩包安装。 |
| **技能（Skills）** | 为特定工程任务注入方法论与规范（如根因分析、测试用例设计、技术文档）。 | 直接编写 Markdown 技能正文。 |
| **连接器（MCP）** | 将外部 MCP 服务提供的工具接入对话，支持 `stdio` 与 Streamable HTTP。 | 配置本地命令行程序或远端 MCP 服务地址。 |
| **外部智能体（ACP）** | 在对话中直接把任务委派给 Codex、Claude Code 或其他 ACP v1 智能体。 | 配置本地可信的 ACP v1 stdio 适配器。 |

在聊天输入框键入 `/` 可快速唤起插件命令与外部智能体。示例插件位于 [`examples/plugins`](examples/plugins)，详细说明见 [扩展使用与开发](docs/plugins.md) 与 [外部智能体（ACP）](docs/external-agents.md)。

<details>
<summary><strong>查看外部智能体（/claude、/codex）配置说明</strong></summary>

1. 安装对应的 ACP 适配器：[Codex ACP](https://github.com/agentclientprotocol/codex-acp) 或 [Claude Agent ACP](https://github.com/agentclientprotocol/claude-agent-acp)，并按上游文档完成本地认证。Java Harness 不安装适配器，也不转交自身模型 API Key。
2. 打开 **设置 → 扩展 → 外部智能体**，添加对应预设（可保留命令名或指定绝对路径），点击 **测试连接** 验证 ACP 握手后启用保存。
3. 回到对话并选择工作区，在消息开头输入 `/claude` 或 `/codex` 加任务描述（例如 `/claude 梳理项目模块之间的依赖`）。该消息直连外部适配器处理，下一条普通消息仍由主模型继续；适配器支持续接时会自动复用同一外部上下文。

> 外部任务在工作区第一个目录运行，不转发 Java Harness 的对话历史、记忆、附件或 MCP 配置，仍以当前系统账户权限运行（Codex 默认 `read-only`，Claude Code 默认 `plan`）。外部程序直接改动的文件无法在 Java Harness 内撤销。

</details>

## 系统架构

<p align="center">
  <img src="docs/assets/architecture.svg" alt="Java Harness 系统架构图" width="100%" />
</p>

```text
java-harness/
├── harness-spi/        Session / Run / Event 与引擎、模型、存储契约（纯 Java 21）
├── harness-core/       执行调度、上下文组装与预算、状态仲裁、取消与截止时间（纯 Java 21）
├── harness-adapters/   OpenAI 兼容 HTTP 适配、工作区工具、文件审核与 JDBC 存储
├── harness-server/     Spring Boot 装配、REST / SSE 接口与 Flyway 数据库迁移
├── harness-web/        前端工作台（Vue 3 + TypeScript + Vite）
├── harness-desktop/    Electron 桌面壳与 Windows 安装包构建
├── examples/           示例插件
└── docs/               架构设计、运行约定与开发文档
```

`harness-core` 与 `harness-spi` 保持**纯 Java 依赖**，不引入 Spring、数据库或 HTTP 客户端；适配层基于 JDK HttpClient、Jackson 与 Spring JDBC 实现；服务层负责 Spring Boot 装配与数据库迁移。

## 安全与使用边界

- 面向**本机单用户**与**可信项目目录**，服务默认仅监听本机回环地址 `127.0.0.1`。
- 模型 API Key 与连接器凭据均在本地加密保存，不会回显。
- 文件修改与命令审批规则属于尽力而为的工程防护，**不是操作系统级安全沙箱**；执行的命令仍具备当前系统账户权限，请勿在不信任的仓库中开启完全访问模式。
- 服务重启后可完整回看历史记录与调用快照，但不会自动续跑中断任务或重放外部调用。

## 文档导航

| 文档 | 内容说明 |
| --- | --- |
| [使用与开发参考](docs/usage-and-development.md) | 环境准备、源码构建、热启动开发、模型配置与工作区操作 |
| [扩展使用与开发](docs/plugins.md) | 插件、技能与 MCP 连接器的安装、配置及开发示例 |
| [外部智能体（ACP）](docs/external-agents.md) | 通过 ACP v1 接入 Codex、Claude Code 与自定义外部智能体、权限模式及续接规则 |
| [群聊与协作工坊](docs/group-chat.md) | 多角色成员、隐藏主持人调度、`@` 接力与终止条件 |
| [固定流水线](docs/flows.md) | 流程生成、岗位流转、人工审核节点与接口说明 |
| [长期记忆](docs/memory.md) | 自动提炼、历史回填、主题图谱与召回规则 |
| [提示词优化](docs/prompt-optimization.md) | 草稿优化流程、上下文与记忆来源 |
| [桌面客户端](docs/desktop.md) | Windows 安装包构建、发布、自动更新与数据目录 |
| [运行约定](docs/runtime.md) · [开发边界](docs/development.md) | 任务生命周期、审批模式、工程边界与编码约定 |
| [功能验证记录](docs/verification.md) · [扩展验证记录](docs/plugin-validation.md) | 各模块功能验证过程与结果 |

## 参与贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库并基于 `master` 创建特性或修复分支。
2. 修改完成后在本地运行完整检查：

   ```bash
   npm --prefix harness-web ci
   npm --prefix harness-desktop ci --ignore-scripts
   npm --prefix harness-web run typecheck
   npm --prefix harness-web test
   npm --prefix harness-desktop test
   sh ./mvnw -B -ntp clean verify
   ```

3. Java 代码使用 [Spotless](https://github.com/diffplug/spotless)（`palantir-java-format`）格式化，请仅格式化改动文件，避免带入无关变更。
4. 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)（如 `feat: ...`、`fix: ...`、`docs: ...`）。
5. 涉及模块架构、数据库迁移或公共 SPI 契约的改动，建议先开 Issue 讨论方案。

GitHub Actions 会在 Windows 与 Linux 环境下自动运行完整构建与测试。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
