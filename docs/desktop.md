# 桌面客户端

`harness-desktop/` 把 Java Harness 打包成 Windows 桌面应用：Electron 提供窗口，内置精简 JRE 运行后端 JAR，用户无需安装 JDK。构建环境与后端启动见 [使用与开发参考](usage-and-development.md)。

## 运行方式

```text
Java Harness.exe（Electron 主进程）
  ├─ 窗口：先显示加载页，后端就绪后加载 http://127.0.0.1:<随机端口>/
  └─ 子进程：resources/backend/runtime/bin/java.exe -jar harness-server.jar
```

- **单实例**：重复启动只会聚焦已打开的窗口。
- **端口**：每次启动选一个空闲的本机端口，只监听 `127.0.0.1`。
- **数据目录**：`%APPDATA%\Java Harness\`。其中 `data\` 存放 H2 数据库、插件和设置密钥，`logs\backend.log` 是后端日志。后端的工作目录也设在这里，避免写入安装目录。卸载时不会删除数据。
- **配置传递**：端口、数据库、插件目录和密钥文件以命令行参数传给后端，不使用环境变量，因此 Agent 执行的命令（例如用户自己的 Spring Boot 项目）不会继承这些配置。用户系统中的其他 `SPRING_*`、`JAVA_TOOL_OPTIONS` 等环境变量仍会影响后端。
- **托盘与退出**：工作台就绪后关闭窗口会收起到系统托盘，任务与通知继续运行；点击托盘可恢复窗口，在托盘菜单选择“退出 Java Harness”才会退出。明确退出后，桌面壳关闭后端的标准输入；后端读到 EOF 后按正常流程关闭（`harness.desktop.exit-with-parent=true`，见 `ParentProcessWatcher`），30 秒内没退出就强制结束。后端在启动完成后才开始监听标准输入，所以启动过程中退出时会等后端启动完成并正常关闭，避免打断数据库迁移。
- **任务通知**：顶栏任务中心汇总普通会话、自动化和群聊轮次，子智能体的待处理状态归入父任务；点击条目、桌面通知可跳转对应会话或群聊。主进程每 2 秒读取轻量任务快照，完成、失败、超时、中断、等待审批或回答时通知；首次连接不补发历史结束通知，关闭通知期间的事件也不会在重新开启后补发。设置 → 常规与托盘菜单共享通知开关，保存在用户目录的 `task-notifications.json`；Windows 的通知设置和勿扰模式仍会影响系统实际展示。
- **异常退出**：Node 在 Windows 上把子进程放进带 kill-on-close 的作业对象，桌面壳被强杀时后端会被系统立即终止，不会遗留进程，但这种情况下后端不会正常关闭。Windows 关机或注销时，桌面壳会尽早通知后端关闭，但仍可能来不及完成。
- **链接**：窗口只停留在本地后端页面，外部链接在系统浏览器中打开。

## 构建安装包

需要 JDK 21（`JAVA_HOME`，需包含 `jlink`、`jdeps`）、Node.js 22.12+，并且能访问 GitHub：`npm ci` 时要下载 Electron，electron-builder 首次构建时要下载 NSIS。打包直接复用 `node_modules/electron/dist` 里已解压的 Electron（`electronDist`），不会重复下载；该目录缺失时，准备脚本会调用 Electron 的安装脚本补齐，并优先使用本机缓存。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
npm --prefix harness-desktop ci
npm --prefix harness-desktop run dist
```

`dist` 依次完成以下步骤：

1. 构建前端，再以 `-Pweb` 打包后端 JAR，复制为 `build/backend/harness-server.jar`。
2. 用 `jdeps` 分析 JAR 内应用类和依赖库用到的 JDK 模块，补上 TLS、Windows 证书库、区域数据等运行期模块，再用 `jlink` 生成 `build/backend/runtime`（约 60MB）。
3. 生成应用图标 `build/icon.ico`。
4. electron-builder 输出安装程序 `dist/Java-Harness-Setup-<版本>-x64.exe`、对应的 `.exe.blockmap` 和 `latest.yml`。安装到当前用户目录，可以自选安装位置，不需要管理员权限。

`npm --prefix harness-desktop run dist:dir` 只输出免安装目录 `dist/win-unpacked/`，适合本地验证。

未配置证书时可构建、发布未签名安装包，并启用自动更新。当前采用固定 GitHub HTTPS 更新源和 SHA-512 文件校验；未签名包不提供签名发布者身份验证。配置证书后会自动签名；代码签名不保证 Windows SmartScreen 一定不再显示信誉提示。

### GitHub 发布

`.github/workflows/release.yml` 在 Windows runner 上执行同样的 `dist` 流程。推送 `v<版本>` 标签（例如 `v0.1.0`）时，先校验标签与 `pom.xml`、`harness-desktop/package.json` 的版本一致，再把安装程序、blockmap、`latest.yml` 和 `harness-server-<版本>.jar` 上传到草稿 GitHub Release，核对附件名称和大小后再公开。在 Actions 页面手动触发时只构建，产物在运行记录的 Artifacts 中下载。发新版本前需同步修改上述两处版本号及对应 lock 文件；首期只发布形如 `1.2.3` 的正式版本。

如需签名发布，可在仓库 Actions Secrets 中配置（可选）：

- `WINDOWS_CSC_LINK`：受信任的 Windows 代码签名 PFX 证书（可使用 electron-builder 支持的 Base64 编码）。
- `WINDOWS_CSC_KEY_PASSWORD`：证书密码。

未配置 `WINDOWS_CSC_LINK` 时按未签名方式发布；配置后使用 `forceCodeSigning`，签名失败时终止构建，并检查安装包内的 `app-update.yml` 包含签名发布者。已配置签名发布者的客户端仍会验证更新包发布者，不会因支持未签名版本而跳过签名校验。证书私钥和 GitHub 发布令牌不会写入客户端。证书轮换应先发布同时信任新旧发布者的过渡版本，再切换签名证书。

### 自动更新

更新源固定为 `song782360037/java-harness` 的 GitHub Releases；必须能够在未登录状态读取正式 Release 及其附件，客户端不携带私有仓库令牌。仓库可以私有，但此时需要另设公开发布仓库并同步调整打包配置和更新源校验，不能直接分发 CI 令牌。

- **适用范围**：Windows x64、使用内置后端的安装版本，允许未签名。开发模式、外接后端、更新配置缺失或无效的安装包不启用更新。
- **检查与下载**：工作台就绪 20 秒后检查一次，运行期间每 6 小时检查一次；只接收更高版本的正式版，后台下载，不在普通退出时自动安装。设置 → 关于与更新可手动检查、查看进度和更新说明。
- **安装**：下载完成后提示“重启更新”。点击后，桌面壳用本次启动的随机令牌请求更新准备；后端协调 API 写请求和定时触发，检查所有会话、子 Agent、群聊规划及命令。有活跃工作时拒绝安装，任务保持原状；用户完成或停止工作后可重试。准备应答丢失时，入口保护最多保留 1 分钟。
- **退出与备份**：确认空闲后关闭后端 stdin，等待 Java 正常退出。更新路径下等待 30 秒仍未退出或异常退出时不启动安装器，不强杀后端。正常退出后，将 H2 数据库和现有 `settings.key` 复制到 `%APPDATA%\Java Harness\backups\pre-update-*\`，全部成功后写入 `update.json`，再启动 NSIS 安装器并重启应用。插件与用户项目不被覆盖。
- **失败恢复**：网络或校验失败不影响当前程序继续运行；关闭后端后出现备份或安装启动错误时，点击“重启当前版本”恢复。安装器启动后由独立安装进程负责替换文件；如果安装失败或新版启动失败，重新运行现有程序或手动安装修复版本，并查看日志。首期不自动降级或恢复数据库。
- **首次接入**：没有更新功能或仍禁止未签名更新的旧客户端，需要先手动安装一次包含当前更新策略的版本。

更新日志为用户目录下的 `logs/updates.log`，超过 10MB 时下次启动轮转。备份不自动删除，需按磁盘空间和恢复需求定期清理。

```powershell
npm --prefix harness-desktop test
node harness-desktop/scripts/verify-update.mjs
# 配置证书进行签名发布时，再检查打包配置中包含签名发布者
node harness-desktop/scripts/verify-update.mjs --require-signature
```

实际发布前需在隔离的 Windows 用户数据目录内安装版本 A，再升级到更高版本 B，确认文件哈希校验、原安装目录覆盖、数据库迁移、健康检查、数据与密钥保留；签名版本还需确认签名验证。开发模式或单元测试不能替代此项验收。

如需人工恢复升级前的数据，先关闭所有客户端并保留当前数据副本，再将备份中的数据库与密钥成对恢复到用户目录的 `data\`，配合对应旧版本程序启动。新版数据库已完成迁移时不能直接切换旧安装包。

## 开发调试

先准备好后端资源，再直接用 Electron 启动：

```powershell
npm --prefix harness-desktop run prepare:backend
npm --prefix harness-desktop start
```

如果要连接一个已经在运行的后端（例如 IDE 中启动的服务），设置 `HARNESS_DESKTOP_BACKEND_URL` 即可，此时不会再拉起内置后端：

```powershell
$env:HARNESS_DESKTOP_BACKEND_URL = 'http://127.0.0.1:8080'
npm --prefix harness-desktop start
```

## 注意事项

- 桌面版数据与命令行启动的 `./data` 相互独立。如需迁移，先关闭两边程序，再把项目的 `data\` 目录内容复制到 `%APPDATA%\Java Harness\data\`。
- 内置 JRE 是按后端依赖裁剪的 JDK 21 运行时，后端、内置 MCP 连接器以及 `command` 写成 `java` 的插件都会使用它。如果插件依赖未包含的模块（例如 `jdk.compiler`）或更高版本的 Java，请在插件配置中填写完整 JDK 的 `java` 绝对路径。Agent 执行的命令以及插件用到的 `node`、`python`、`git` 等，仍从系统 `PATH` 查找。
- `logs\backend.log` 超过 10MB 时，下次启动会轮转为 `backend.log.1`。
- 目前只打包 Windows x64。
