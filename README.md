# Java Harness

Java 原生 Agent 运行时与本地 Web 编程工作台。目前已实现基于模拟模型的最小执行闭环。

## 当前范围

已实现会话创建与切换、单会话单活动任务、模拟模型流式输出、取消、超时和失败状态、H2 文件持久化、Flyway 迁移、SSE 事件补发及刷新恢复。

当前无需 API Key 或外部数据库。模拟模型只处理本次输入，不具备真实模型的推理或多轮上下文理解能力。尚未实现真实模型接入、工具执行、工作区文件操作、审批、插件加载或自动续跑。

## 工程结构

```text
java-harness/
  harness-spi/          Session / Run / Event 与引擎、模型、存储契约
  harness-core/         执行调度、状态仲裁、取消与截止时间
  harness-adapters/     模拟模型与 JDBC 存储
  harness-server/       Spring Boot 装配与 Web 入口，依赖 core、adapters
  harness-web/          Vue 3 + TypeScript + Vite
  .mvn/wrapper/         固定 Maven 版本的 Wrapper
  .github/workflows/    Windows / Linux 构建工作流
  docs/                设计与开发说明
```

core/spi 保持普通 Java 依赖；adapters 使用 Spring JDBC 实现存储；server 负责 Spring Boot、Flyway、REST/SSE 和应用装配。

## 环境

- JDK 21，设置 `JAVA_HOME`。
- Node.js 22.12+，推荐 Node.js 24 LTS；npm。
- Maven Wrapper 固定 Maven 3.9.16，无需单独安装 Maven。首次构建需要联网下载。

项目使用 Spring Boot 3.5.16。前端依赖版本固定，并提交 `package-lock.json`。

Windows 当前终端配置示例，请替换为自己的 JDK 路径：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## 后端构建与启动

在项目根目录执行：

```powershell
.\mvnw.cmd -B -ntp clean verify
java -jar harness-server\target\harness-server-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:8080`。检查 [应用存活状态](http://127.0.0.1:8080/actuator/health)，预期返回 `{"status":"UP"}`。

仅构建后端时不包含首页，访问 `/` 返回 404 是预期行为。

IDEA 导入根目录 `pom.xml`，选择 JDK 21，运行 `HarnessApplication` 即可。使用命令行开发模式时，先安装模块依赖：

```powershell
.\mvnw.cmd -B -ntp install
.\mvnw.cmd -pl harness-server spring-boot:run
```

Linux/macOS 使用 `sh ./mvnw` 替代 `.\mvnw.cmd`，路径使用 `/`。

## 前端开发

另开一个终端，在项目根目录执行：

```powershell
npm --prefix harness-web ci
npm --prefix harness-web run dev
```

访问 [前端开发页面](http://127.0.0.1:5173)。开发代理将 `/api` 与 `/actuator/health` 转发到后端，不需要为本地开发开放 CORS。

更换后端端口时设置 `HARNESS_PORT`；前端在 `harness-web/.env.local` 中设置对应 `HARNESS_BACKEND_URL`，示例见 `.env.example`。前端端口固定为 5173，占用时会明确报错。

## 打包为一个可运行 JAR

```powershell
npm --prefix harness-web ci
npm --prefix harness-web run build
.\mvnw.cmd -B -ntp -Pweb clean verify
java -jar harness-server\target\harness-server-0.1.0-SNAPSHOT.jar
```

此时访问 [Java Harness](http://127.0.0.1:8080/) 使用工作台。前端资源位于 JAR 的 `BOOT-INF/classes/static`，最终运行只需要 JDK，不需要 Node.js。

`web` profile 不执行 npm，只复制已经构建的 `harness-web/dist`；缺少 `dist/index.html` 时构建失败并给出提示。修改前端后需重新执行前端构建。切换打包模式时使用 `clean`，避免旧静态资源残留。

## 检查

```powershell
npm --prefix harness-web run typecheck
npm --prefix harness-web run build
.\mvnw.cmd -B -ntp clean verify
```

自动化测试覆盖状态、并发、取消、超时、事务、文件库恢复和实际 HTTP/SSE 接口。GitHub Actions 预置 Windows/Linux 构建，实际 CI 结果以远程运行记录为准。

## 使用与数据

1. 点击“新建会话”，输入文本并发送，观察模拟输出。
2. 选择“中途失败”，查看部分文本与失败状态。
3. 选择“等待超时 / 手动停止”，点击停止或等待默认 15 秒超时。
4. 刷新页面保留会话与已保存内容；关闭浏览器不会取消任务。

数据默认写入**进程工作目录**下的 `data/harness.mv.db`，启动时自动执行迁移。IDEA 和命令行请使用同一个工作目录以访问同一份数据。不要同时启动两个实例操作这份数据库。

正常退出会请求取消活动任务；异常退出后的活动任务在下次启动时标记为 `INTERRUPTED`，保留已提交输出，不自动重试。终态任务不再接受输出。模拟超时可以通过 `--harness.run-timeout=30s` 调整。

API、状态和事件约定见 [最小运行闭环](docs/runtime.md)。此版本用于本机单用户体验，默认绑定回环地址并检查 API Host/Origin；尚未提供登录、多用户隔离或远程部署能力。

参阅 [开发边界](docs/development.md)、[本次验证记录](docs/verification.md) 和 [架构设计稿](docs/architecture-proposal.md)。设计稿包含后续规划，不代表已经实现。
