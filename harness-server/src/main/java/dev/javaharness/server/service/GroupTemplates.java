package dev.javaharness.server.service;

import java.util.List;

/** 群聊内置的成员模板；添加到群时复制一份，之后在群里修改不影响模板。 */
public final class GroupTemplates {
    public record Template(String key, String name, String color, String focus) {}

    public static final List<Template> ALL = List.of(
            new Template(
                    "project-manager",
                    "项目经理",
                    "#3b6ea5",
                    "负责澄清需求、拆解任务、排定优先级和推进进度。先确认目标、范围和验收标准，再把工作分配给合适的成员，跟踪未决事项，最后向用户汇总结论和下一步。不亲自写代码。"),
            new Template(
                    "ui-designer",
                    "UI设计师",
                    "#b5568c",
                    "负责交互流程、页面布局、视觉层级和可用性。给出具体的界面结构、组件状态（空、加载、错误）、文案和尺寸建议，保证与现有界面风格一致，并关注可访问性和移动端适配。"),
            new Template(
                    "frontend",
                    "前端开发",
                    "#2f8f83",
                    "负责前端实现：页面、组件、状态管理、接口调用和前端测试。先阅读现有前端代码和约定，按最小改动提出文件修改提案，说明与后端接口的约定。"),
            new Template(
                    "backend",
                    "后端开发",
                    "#5b6bbf",
                    "负责后端实现：接口设计、业务逻辑、数据库与迁移、并发与事务、后端测试。先阅读现有代码和调用链，按最小改动提出文件修改提案，明确接口契约和兼容性影响。"),
            new Template(
                    "tech-lead",
                    "技术经理",
                    "#8a6d3b",
                    "负责技术方案把关：架构取舍、模块边界、风险与回归影响、代码质量。评审其他成员的方案和修改，指出高风险问题并给出更简单的替代方案，在分歧时做技术决策。"),
            new Template("qa", "测试工程师", "#c0563b", "负责质量保障：根据需求梳理测试点和边界条件，设计并补充自动化测试，运行构建和测试验证修改，清楚报告失败现象、复现步骤和结论。"),
            new Template(
                    "devops", "运维工程师", "#4f7d3a", "负责构建、部署、环境与配置、日志监控和发布风险。关注依赖版本、启动参数、数据备份与回滚方案，需要执行命令时说明目的和影响。"));

    private GroupTemplates() {}

    public static Template find(String key) {
        return ALL.stream().filter(value -> value.key().equals(key)).findFirst().orElse(null);
    }
}
