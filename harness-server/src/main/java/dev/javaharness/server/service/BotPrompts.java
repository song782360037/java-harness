package dev.javaharness.server.service;

import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Summary;
import dev.javaharness.spi.model.TokenCounter;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bot 聊天与后台任务的系统指引和上下文组装。 */
public final class BotPrompts {
    private static final DateTimeFormatter NOW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (z)");

    private BotPrompts() {}

    public static String chatInstructions(
            Bot bot,
            String userDisplayName,
            List<Bot> roster,
            List<Playbook> playbooks,
            List<Routine> routines,
            String workspaceName) {
        var sb = new StringBuilder();
        sb.append("你是桌面 AI 助手团队中的「").append(bot.name()).append("」");
        if (bot.main()) sb.append("（主 Bot / 团队总管）");
        sb.append("。\n");
        sb.append("当前时间：")
                .append(ZonedDateTime.now(ZoneId.systemDefault()).format(NOW_FMT))
                .append('\n');
        if (userDisplayName != null && !userDisplayName.isBlank()) {
            sb.append("用户的称呼：").append(userDisplayName).append('\n');
        }
        if (!bot.roleSummary().isBlank()) {
            sb.append("你的定位：").append(bot.roleSummary()).append('\n');
        }
        if (!workspaceName.isBlank()) {
            sb.append("你当前绑定的项目工作区：").append(workspaceName).append('\n');
        }
        if (!bot.systemPrompt().isBlank()) {
            sb.append("\n### 角色指令\n").append(bot.systemPrompt().strip()).append('\n');
        }

        sb.append("\n### 团队成员花名册\n");
        for (Bot member : roster) {
            sb.append("- ")
                    .append(member.name())
                    .append("（id=")
                    .append(member.id())
                    .append(member.main() ? "，主 Bot" : "")
                    .append("）：")
                    .append(member.roleSummary().isBlank() ? "通用助手" : member.roleSummary())
                    .append('\n');
        }

        if (!playbooks.isEmpty()) {
            sb.append("\n### 你已学会的做法（SOP）\n");
            for (Playbook playbook : playbooks) {
                sb.append("- **")
                        .append(playbook.name())
                        .append("**（id=")
                        .append(playbook.id())
                        .append("）");
                if (!playbook.whenToUse().isBlank()) sb.append("｜适用场景：").append(playbook.whenToUse());
                sb.append("\n  步骤：").append(playbook.steps().replace("\n", " / "));
                if (!playbook.notes().isBlank())
                    sb.append("\n  注意：").append(playbook.notes().replace("\n", " / "));
                sb.append('\n');
            }
        }

        if (!routines.isEmpty()) {
            sb.append("\n### 你的例行任务\n");
            for (Routine routine : routines) {
                sb.append("- ")
                        .append(routine.name())
                        .append("（id=")
                        .append(routine.id())
                        .append("，")
                        .append(routine.scheduleKind())
                        .append(routine.cron().isBlank() ? "" : " " + routine.cron())
                        .append(routine.enabled() ? "，启用中" : "，已暂停")
                        .append("）\n");
            }
        }

        sb.append("""

                ### 聊天与干活分离规则（重要）
                1. 你当前处于轻量聊天窗口，只负责沟通、拆解、派活与总结，本身不直接读写磁盘或执行终端命令。
                2. 凡是需要动手执行的工作（读/写代码或资料库、排查问题、跑命令、调外部 MCP、整理长报告），请立即调用 `start_task` 启动独立后台任务，然后简短告知用户任务已派发。
                3. 如果事情更适合团队里的其他专职 Bot 处理，请调用 `delegate_to_bot` 静默派给它，完成后结果会自动回传给你再向用户汇总。
                4. 回复中提到团队里的任何 Bot 时，请写成 `[Bot名称](bot:BotID)`，界面会自动渲染为可点击的头像标签。
                5. 用户要求「以后都这么做」或沉淀流程时，直接调用 `save_playbook` 保存做法；用户要求每天/每周定时做某事时，直接调用 `create_routine` 设定例行任务；用户要求新建/调整 Bot 时，直接调用 `create_bot` / `update_bot` 生效。
                6. 不要编造尚未完成的后台任务执行结果；如不确定历史细节，可调用 `search_history` / `read_history` 查阅。
                7. 如适合引导下一步，可在回复最后单独加一行 `[建议: 快捷问题1 | 快捷问题2]`（最多 3 条，不需要时可省略）。
                """);
        return sb.toString();
    }

    public static String taskInstructions(
            Bot bot, Playbook playbook, String workspaceName, String originBotName, boolean delegated) {
        var sb = new StringBuilder();
        sb.append("你正在作为「").append(bot.name()).append("」的独立后台任务执行引擎干活。\n");
        sb.append("当前时间：")
                .append(ZonedDateTime.now(ZoneId.systemDefault()).format(NOW_FMT))
                .append('\n');
        if (!bot.roleSummary().isBlank()) {
            sb.append("角色定位：").append(bot.roleSummary()).append('\n');
        }
        if (!bot.systemPrompt().isBlank()) {
            sb.append("角色要求：").append(bot.systemPrompt().strip()).append('\n');
        }
        if (delegated && originBotName != null && !originBotName.isBlank()) {
            sb.append("本次任务由「").append(originBotName).append("」委派给你完成，完成后结论将回传给它汇总。\n");
        }
        if (playbook != null) {
            sb.append("\n### 请严格按照指定做法（SOP）执行\n");
            sb.append("做法名称：").append(playbook.name()).append('\n');
            if (!playbook.whenToUse().isBlank())
                sb.append("适用场景：").append(playbook.whenToUse()).append('\n');
            sb.append("步骤：\n").append(playbook.steps()).append('\n');
            if (!playbook.notes().isBlank())
                sb.append("注意事项：\n").append(playbook.notes()).append('\n');
        }
        sb.append("\n### 工具与目录说明\n");
        sb.append(
                "- `library_list` / `library_read` / `library_write` / `library_search` 操作 Bot 本地资料库（自动落盘，适合输出报告、笔记和中间文件）。\n");
        sb.append(
                "- `save_app` / `update_app_data` 可创建或更新交互式单文件 HTML 小程序（页面内可用 `await window.harnessBot.getData()`、`await window.harnessBot.setData(obj)`、`window.harnessBot.onData(fn)` 与 `window.harnessBot.sendMessage(text)`）；产出小程序后请在结论中写出 `[小程序名称](app:小程序ID)`，聊天中会自动渲染成可点击打开的小程序卡片。\n");
        if (!workspaceName.isBlank()) {
            sb.append("- 你还绑定了项目工作区「").append(workspaceName).append("」，可按审批规则使用工作区读写、Git 与命令工具。\n");
        } else {
            sb.append("- 当前未绑定代码项目工作区，请使用资料库工具与已配置的 MCP 工具完成任务。\n");
        }
        sb.append("- 完成后请给出清晰、结构化的最终结论（包含关键产出文件路径或核心数据），该结论会直接呈现在聊天窗口中。\n");
        return sb.toString();
    }

    /** 组装 <bot_chat> 上下文块：保留滚动摘要 + 可选工作区长期记忆 + 预算内最新的聊天记录。 */
    public static String chatContext(Summary summary, List<Entry> entries, TokenCounter counter, int maxTokens) {
        return chatContext(summary, entries, "", counter, maxTokens);
    }

    public static String chatContext(
            Summary summary, List<Entry> entries, String memoryText, TokenCounter counter, int maxTokens) {
        if (maxTokens <= 128) return "";
        var header = new StringBuilder("<bot_chat>\n");
        if (memoryText != null && !memoryText.isBlank()) {
            header.append(escapeTags(memoryText)).append('\n');
        }
        if (summary != null && !summary.summaryText().isBlank()) {
            header.append("<earlier_summary through_seq=\"")
                    .append(summary.throughSeq())
                    .append("\">\n")
                    .append(escapeTags(summary.summaryText()))
                    .append("\n</earlier_summary>\n");
        }
        String footer = "</bot_chat>";
        int used = counter.count(header.toString()) + counter.count(footer);
        if (used >= maxTokens) return "";

        var chosen = new ArrayList<String>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            String block = formatEntry(entry);
            int cost = counter.count(block);
            if (used + cost > maxTokens) {
                if (chosen.isEmpty() && maxTokens - used > 80) {
                    int maxChars = Math.max(200, (maxTokens - used) * 2);
                    String clipped = block.length() > maxChars ? block.substring(0, maxChars) + "…\n</entry>\n" : block;
                    chosen.add(clipped);
                }
                break;
            }
            chosen.add(block);
            used += cost;
        }
        Collections.reverse(chosen);
        for (String block : chosen) header.append(block);
        header.append(footer);
        return header.toString();
    }

    private static String formatEntry(Entry entry) {
        var sb = new StringBuilder();
        sb.append("<entry seq=\"")
                .append(entry.seq())
                .append("\" kind=\"")
                .append(entry.kind())
                .append("\"");
        if (!entry.taskId().isBlank())
            sb.append(" task_id=\"").append(entry.taskId()).append("\"");
        sb.append(">\n");
        if (entry.quoteSeq() != null && !entry.quotePreview().isBlank()) {
            sb.append("[引用 #")
                    .append(entry.quoteSeq())
                    .append("]: ")
                    .append(escapeTags(entry.quotePreview()))
                    .append('\n');
        }
        sb.append(escapeTags(entry.content())).append("\n</entry>\n");
        return sb.toString();
    }

    private static String escapeTags(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)<(/?(?:bot_chat|entry|earlier_summary))", "&lt;$1");
    }
}
