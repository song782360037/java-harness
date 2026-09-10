# 验证记录

日期：2026-09-09。

## 当前阶段：最小运行闭环

- 前端 `npm run build`：TypeScript 检查与 Vite 生产构建通过。
- 后端 Maven `-Pweb verify`：10 项测试，0 失败、0 错误、0 跳过；可运行 JAR 打包通过。
- 运行测试 8 项：正常完成和 Unicode 事件投影、并发幂等及忙碌拒绝、取消等待收尾并丢弃迟到输出、排队取消不调用模型、超时/失败区分、事件插入失败后的事务回滚、文件库重开及活动状态恢复、非法输入/终态不可复活。
- HTTP 集成测试 2 项：实际随机端口服务上的提交/重试/取消/Origin 检查，以及 SSE 历史读取、Last-Event-ID 补发、断开不取消和快照读取。
- 浏览器实际操作：创建会话、提交、输出完成、刷新恢复、停止任务正常。
- 实际强制终止 JVM 并重启：活动 Run 恢复为 INTERRUPTED，已提交文本逐字保留，Run 数量不变，浏览器自动重连后显示中断原因。
- 实际正常关闭 JVM：同时存在活动 Run 和 SSE 订阅时，服务正常优雅退出；重新打开临时文件库确认任务终态为 CANCELLED。已调整关闭顺序，先结束 SSE 和引擎，再停止 Web 服务。
- 页面已做桌面截图检查。前端自动化测试、移动端视觉验证、Linux 运行及远程 CI 未执行。

本阶段仍使用模拟模型。以上结果不能作为真实模型、文件操作、工具执行、审批或生产吞吐的验证。

运行验证使用 `harness-server/target/ui-smoke` 临时文件库与 18080 端口，没有写入默认 data 用户数据库。正式默认超时为 15 秒；浏览器崩溃恢复验证临时设为 120 秒。

最终仅修正前端重连成功后的过期错误提示，重新通过前端类型检查和构建，再以 `-Pweb -DskipTests package` 更新合并 JAR；该次打包没有重跑测试，后端 10 项测试结果来自此前相同 Java 源码的 verify。临时验证服务已停止。

## 初始阶段：工程骨架（历史记录）

本地环境：Windows PowerShell、Amazon Corretto 21.0.10、Maven Wrapper 3.9.16、Node.js 23.11.0、npm 10.9.2。项目推荐使用 Node.js 24 LTS；本次实际使用的是机器已有且满足引擎要求的 Node.js 23。

| 检查 | 结果 |
| --- | --- |
| `mvnw.cmd -B -ntp clean verify` | 父工程及四个后端模块构建通过 |
| `npm ci --registry=https://registry.npmjs.org` | 锁文件安装通过 |
| `npm run build` | Vue TypeScript 检查与 Vite 生产构建通过 |
| `mvnw.cmd -B -ntp -Pweb clean verify` | 前端资源合并进可运行 JAR，构建通过 |
| 纯后端 JAR | `/actuator/health` 返回 UP；`/` 返回预期的 404 |
| 合并 JAR | 首页、JS、CSS 均返回 200；health 返回 UP |
| 浏览器打开合并 JAR 首页 | 正常渲染“本地编程工作台”占位页 |
| Vite 开发服务 | 首页返回 200；健康检查代理转发后返回 UP |
| 构建配置检查 | POM XML 有效；Wrapper/锁文件未包含本机目录和镜像地址 |
| Java 源码范围检查 | 一个 Spring Boot 启动类，三个 package-info；没有业务类 |

集成检查使用临时端口 18080、15173，验证后已停止服务。默认端口仍是 8080、5173。

合并打包时修正了文件检查规则对含 `..` 路径的判定问题，改为使用 Maven 多模块根目录定位前端产物。

当前没有业务测试用例，Maven 输出 `No tests to run`；以上结果不代表任何 Agent 或业务能力经过测试。Windows/Linux GitHub Actions 仅已添加配置，尚未在远程运行。Linux 启动未在本机验证。
