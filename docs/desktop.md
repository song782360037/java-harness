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
- **退出**：关闭窗口后，桌面壳关闭后端的标准输入；后端读到 EOF 后按正常流程关闭（`harness.desktop.exit-with-parent=true`，见 `ParentProcessWatcher`），30 秒内没退出就强制结束。后端在启动完成后才开始监听标准输入，所以如果在启动过程中关闭窗口，桌面壳会等后端启动完成并正常退出，避免打断数据库迁移。
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
4. electron-builder 输出安装程序 `dist/Java Harness-Setup-<版本>.exe`。安装到当前用户目录，可以自选安装位置，不需要管理员权限。

`npm --prefix harness-desktop run dist:dir` 只输出免安装目录 `dist/win-unpacked/`，适合本地验证。

安装包未做代码签名，首次运行时 Windows SmartScreen 会提示风险，需要选择“仍要运行”。

### GitHub 发布

`.github/workflows/release.yml` 在 Windows runner 上执行同样的 `dist` 流程。推送 `v<版本>` 标签（例如 `v0.1.0`）时，先校验标签与 `pom.xml`、`harness-desktop/package.json` 的版本一致，再把安装程序和 `harness-server-<版本>.jar` 发布到 GitHub Release；在 Actions 页面手动触发时只构建，产物在运行记录的 Artifacts 中下载。发新版本前需同步修改上述两处版本号。

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
