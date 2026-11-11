<div align="center">

# Java Harness

**用 Java 构建的本地 AI 编程工作台**

连接你选择的模型，在对话中理解项目、定位问题、审查代码，<br>
在你的审核下修改文件、执行命令与测试。

[![Build](https://github.com/song782360037/java-harness/actions/workflows/build.yml/badge.svg)](https://github.com/song782360037/java-harness/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/song782360037/java-harness?display_name=tag&sort=semver)](https://github.com/song782360037/java-harness/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![Vue](https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux-lightgrey)

[快速开始](#快速开始) · [功能特性](#功能特性) · [插件与扩展](#插件与扩展) · [架构](#架构) · [文档](#文档) · [参与贡献](#参与贡献)

</div>

---

## 简介

Java Harness 是一个运行在本机的 AI 编程助手。它把模型对话、项目工作区、代码工具和可复用的工作流放进同一个界面：

- **模型自选**：接入任意兼容 OpenAI Chat Completions 接口的服务，在对话中随时切换模型和思考强度。
- **改动可控**：文件修改先以 Diff 呈现，命令按审批模式确认，每一次工具调用都有完整记录。
- **数据在本地**：会话、设置与记忆保存在本机 H2 数据库，服务只监听 `127.0.0.1`。
- **可扩展**：通过插件、技能和 MCP 连接器组合团队自己的工作流。

提供 **Windows 桌面客户端**（内置精简 JRE，无需安装 JDK），也可以作为单个可运行 JAR 在任意装有 JDK 21 的系统上启动。

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 多模型对话 | 管理多个模型提供商，在聊天中切换模型和思考强度；支持流式回答、Markdown、代码块与表格。 |
| 项目工作区 | 为项目设置名称、图标和颜色，关联一个或多个本地目录，在多个会话中复用。 |
| 代码探索 | 浏览目录、读取文件、搜索文本、查看 Git 状态与差异，基于真实源码分析问题。 |
| 文件修改审核 | 查看 AI 提出的新建或修改，通过 Diff 审核后应用或拒绝；已应用的修改可在会话空闲时撤销。 |
| 审批模式 | 手动 / 智能 / 完全访问三档；高危命令在任何模式下都需人工确认，并支持 deny / ask / allow 命令规则。 |
| 命令与测试 | 审批后运行命令，查看输出、随时停止，让模型根据结果继续分析或修正。 |
| 子智能体 | 将相互独立的子任务并行委派给子智能体（只读探索 `explore` / 可提出修改的 `general`），在独立上下文中完成后只带回结论。 |
| 群聊 | 建群并拉入 AI 成员（内置项目经理、设计、前后端、测试、运维等模板），由隐藏主持人分工调度，成员间可互相 @。 |
| 定时自动化 | 设定提示词、工作区、模型与频率（每天 / 工作日 / 每周 / 每小时 / cron / 手动），到点自动新建会话执行。 |
| 长期记忆 | 开启后从对话中自动生成记忆，在主题图谱中查看、纠正、删除并追溯来源。 |
| 提示词优化 | 结合最近对话、文件引用、附件与相关记忆优化输入草稿，回填后由你决定是否发送。 |
| 任务中心 | 跨会话查看运行中、待审批和待回答的任务；桌面端支持系统通知与托盘常驻。 |
| 执行记录 | 查看每次调用的模型输入快照、工具参数与结果，刷新或重启后仍可回看。 |
| 界面偏好 | 明亮 / 黑暗 / 跟随系统主题，可调字号、发送快捷键与消息跟随方式。 |

## 快速开始

### 方式一：下载桌面客户端（Windows）

前往 [Releases](https://github.com/song782360037/java-harness/releases) 下载 `Java-Harness-Setup-<版本>-x64.exe`，安装后即可使用。安装包内置运行时，支持应用内自动更新，数据保存在 `%APPDATA%\Java Harness\`。

> 安装包若未做代码签名，首次运行时 Windows SmartScreen 可能提示风险，确认来源后选择“仍要运行”。

### 方式二：从源码构建

**环境要求**

- JDK 21（设置 `JAVA_HOME`）
- Node.js 22.12+（推荐 24 LTS）与 npm
- 无需单独安装 Maven，项目自带 Maven Wrapper

**构建并运行**

```bash
git clone https://github.com/song782360037/java-harness.git
cd java-harness

# 构建前端
npm --prefix harness-web ci
npm --prefix harness-web run build

# 打包包含前端的后端 JAR（Windows 使用 .\mvnw.cmd）
sh ./mvnw -B -ntp -Pweb clean verify

# 启动
java -jar harness-server/target/harness-server-0.2.1.jar
```

浏览器访问 <http://127.0.0.1:8080/>。数据默认写入启动目录下的 `data/`。

### 首次使用

1. 进入 **设置 → 模型与提供商**，添加提供商（API 地址与 Key），再添加模型。
2. 点击 **新对话**，在输入框底部选择工作区、模型和思考强度。
3. 让它帮你了解项目、定位问题或修改代码；文件修改和命令会在执行前请你确认。

开发模式（前后端分别热启动）、端口配置等见 [使用与开发参考](docs/usage-and-development.md)。

## 插件与扩展

在 **设置 → 扩展** 中发现和管理插件、技能与连接器（MCP），均提供官方内容和自定义添加入口。

| 类型 | 用途 | 自定义方式 |
| --- | --- | --- |
| 插件 | 将技能、命令与工具组织成可复用的工作流，例如代码审查、项目理解和发布检查。 | 从本地目录或 ZIP 安装插件包。 |
| 技能 | 为特定任务提供方法与指令，例如前端设计、根因分析、测试用例设计和技术文档。 | 直接填写 Markdown 技能正文。 |
| 连接器（MCP） | 将 MCP 服务提供的工具接入对话，支持 `stdio` 与 Streamable HTTP。 | 配置本地程序或远端 MCP 服务。 |

在聊天输入框输入 `/` 可唤起插件命令。示例插件位于 [`examples/plugins`](examples/plugins)，完整说明见 [扩展使用与开发](docs/plugins.md)。

## 架构

```text
java-harness/
├── harness-spi/        Session / Run / Event 与引擎、模型、存储契约
├── harness-core/       执行调度、上下文组装与预算、状态仲裁、取消与截止时间
├── harness-adapters/   OpenAI 兼容 HTTP 适配、工作区工具、文件审核与 JDBC 存储
├── harness-server/     Spring Boot 装配、REST / SSE 接口与数据库迁移
├── harness-web/        前端工作台（Vue 3 + TypeScript + Vite）
├── harness-desktop/    Electron 桌面壳与 Windows 安装包构建
├── examples/           示例插件
└── docs/               设计与开发文档
```

`harness-core` 与 `harness-spi` 保持纯 Java 依赖，不依赖 Spring、数据库或 HTTP；适配层使用 JDK HttpClient、Jackson 和 Spring JDBC；服务层负责 Spring Boot、Flyway 迁移与应用装配。

**技术栈**：Java 21 · Spring Boot 3.5 · H2 · Flyway · Vue 3 · TypeScript · Vite · Electron

## 安全与使用边界

- 面向**本机单用户**和**可信项目目录**，服务仅监听本机回环地址。
- 文件修改需要审核（或按审批模式自动应用），命令需要审批，插件工具保留执行记录；但运行的程序仍具有当前系统账户的权限。
- 命令审批规则是尽力而为的文本匹配，**不是安全沙箱**。请勿在不信任的仓库中开启完全访问模式。
- 模型 API Key 与连接器凭据加密保存在本地，不会回显。
- 服务重启后可查看历史，但不会自动续跑任务或重放外部调用。

## 文档

| 文档 | 内容 |
| --- | --- |
| [使用与开发参考](docs/usage-and-development.md) | 环境准备、构建启动、模型配置与工作区操作 |
| [扩展使用与开发](docs/plugins.md) | 插件、技能和 MCP 的安装、配置及开发示例 |
| [桌面客户端](docs/desktop.md) | 安装包构建、发布、自动更新与数据目录 |
| [开发边界](docs/development.md) | 工程结构、配置与开发约定 |
| [运行约定](docs/runtime.md) | 任务、工具、审批模式、审核与接口说明 |
| [长期记忆](docs/memory.md) | 自动生成、历史回填、图谱和召回规则 |
| [提示词优化](docs/prompt-optimization.md) | 草稿优化、上下文与记忆来源 |
| [群聊](docs/group-chat.md) | 成员、主持人调度、@ 接力与终止条件 |
| [功能验证记录](docs/verification.md) · [扩展验证记录](docs/plugin-validation.md) | 各功能的验证过程与结果 |

## 参与贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本仓库并基于 `master` 创建分支。
2. 修改后在本地运行检查：

   ```bash
   npm --prefix harness-web ci
   npm --prefix harness-desktop ci --ignore-scripts
   npm --prefix harness-web run typecheck
   npm --prefix harness-web test
   npm --prefix harness-desktop test
   sh ./mvnw -B -ntp clean verify
   ```

3. Java 代码使用 [Spotless](https://github.com/diffplug/spotless)（palantir-java-format）格式化，请只格式化自己改动的文件，避免带入无关格式变更。
4. 提交信息建议遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)，例如 `feat: ...`、`fix: ...`。
5. 涉及架构、数据库迁移或公共接口的改动，建议先开 Issue 讨论方案。

GitHub Actions 会在 Windows 与 Linux 上运行完整构建。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
