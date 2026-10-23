---
name: dependencies
description: 用户需要查看 Maven POM 中声明的依赖摘要时使用。
---

读取用户指定的 POM，将实际 XML 内容作为 `pomXml` 参数调用本插件的 `summarize_dependencies` 工具。

该工具只分析明确传入的 XML，不读取文件、不运行 Maven、不解析传递依赖或属性替换。报告中应区分声明依赖和 Maven 实际解析的完整依赖图；没有执行 Maven 时不要声称已完成依赖解析。

只传入当前任务需要的内容；工具调用仍遵循宿主审批。
