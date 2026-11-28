package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.BotFile;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.Fact;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bot 聊天窗口专用轻量协调工具集（严格与后台动手工具分离）：
 * 不挂载磁盘读写、Shell 命令或 MCP，仅支持派发后台任务、跨 Bot 委派、管理团队、沉淀做法、切换模型与历史检索。
 */
public final class BotChatTools implements WorkspaceTools {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 由 BotOrchestrator 实现的聊天侧动作回调。 */
    public interface Actions {
        Task startTask(
                String botId,
                String originBotId,
                String title,
                String instruction,
                String playbookId,
                String modelProfileId,
                boolean silentTargetChat);

        default Task startTask(
                String botId,
                String originBotId,
                String title,
                String instruction,
                String playbookId,
                String modelProfileId,
                String miniAppId,
                boolean silentTargetChat) {
            return startTask(botId, originBotId, title, instruction, playbookId, modelProfileId, silentTargetChat);
        }

        List<Bot> listBots();

        Bot resolveBot(String idOrName);

        Bot createBot(
                String name, String roleSummary, String systemPrompt, String shape, String color, String workspaceId);

        Bot updateBotFromTool(
                String botIdOrName,
                String name,
                String roleSummary,
                String systemPrompt,
                String workspaceId,
                String approvalMode);

        void archiveBotFromTool(String botIdOrName);

        Playbook savePlaybookFromTool(
                String botId, String id, String name, String whenToUse, String steps, String notes);

        void deletePlaybookFromTool(String botId, String playbookId);

        String setModelFromTool(String callerBotId, String targetBotIdOrName, String scope, String modelProfileId);

        List<Entry> searchHistory(String botId, String query, int limit);

        List<Entry> readHistory(String botId, long aroundSeq, int window);

        List<BotFile> listLibraryFiles(String botId);

        Routine createRoutineFromTool(
                String botId,
                String name,
                String instruction,
                String scheduleKind,
                String scheduleTime,
                Integer scheduleWeekday,
                String cron,
                String timezone,
                String playbookId,
                String modelProfileId);

        Routine toggleRoutineFromTool(String botId, String routineId, boolean enabled);

        void deleteRoutineFromTool(String botId, String routineId);

        List<Routine> listRoutinesFromTool(String botId);

        List<MiniApp> listAppsFromTool(String botId);

        Fact rememberFromTool(String botId, String scope, String text);

        void forgetFromTool(String botId, String factId);

        Task stopTaskFromTool(String botId, String taskId);

        Routine setRoutinePlaybookFromTool(String botId, String routineId, String playbookId);

        String listBotLevelsFromTool();

        default String installSkillFromTool(String botId, String targetBotIdOrName, String source) {
            throw new UnsupportedOperationException("技能服务未启用");
        }

        default String removeSkillFromTool(String botId, String targetBotIdOrName, List<String> names) {
            throw new UnsupportedOperationException("技能服务未启用");
        }
    }

    private static final List<ToolDefinition> DEFINITIONS = List.of(
            tool(
                    "start_task",
                    "为当前 Bot 启动一个独立后台任务去完成动手活（查代码/写文件/跑命令/写报告/调用 MCP/制作或修改小程序）",
                    map(
                            "title", prop("string", "简短任务标题（1-80 字）"),
                            "instruction", prop("string", "详细清晰的执行指令，后台任务看不到聊天上下文，请把背景写全"),
                            "playbook_id", prop("string", "可选：指定按照某个已学会的做法 ID 执行"),
                            "model_profile_id", prop("string", "可选：为本次后台任务单独指定模型配置 ID"),
                            "mini_app_id", prop("string", "可选：若本次任务旨在修改/修复某个已有小程序，传入其 ID")),
                    List.of("title", "instruction")),
            tool(
                    "delegate_to_bot",
                    "将一项具体工作静默委派给团队中的另一位专职 Bot 在后台执行，完成后结论会自动回传给你汇总",
                    map(
                            "bot", prop("string", "目标专职 Bot 的名称或 ID"),
                            "title", prop("string", "简短任务标题（1-80 字）"),
                            "instruction", prop("string", "交给该专职 Bot 的完整任务说明与预期输出"),
                            "playbook_id", prop("string", "可选：指定该 Bot 的某个做法 ID")),
                    List.of("bot", "title", "instruction")),
            tool("list_bots", "查看当前团队里所有可用 Bot 的名称、ID、定位和工作区", Map.of(), List.of()),
            tool(
                    "create_bot",
                    "招募并立即创建一个新的专职 Bot 加入团队（直接生效）",
                    map(
                            "name",
                            prop("string", "Bot 名称（1-30 字，团队内唯一）"),
                            "role_summary",
                            prop("string", "一句话岗位职责"),
                            "system_prompt",
                            prop("string", "该 Bot 的详细人设与工作规范"),
                            "shape",
                            prop(
                                    "string",
                                    "可选形象（30 种）：orb/cube/radar/visor/astro/clockwork/fox/cat/owl/bear/bunny/shiba/leaf/clover/panda/penguin/frog/octopus/hex/prism/pill/chef/wizard/coffee/star/cloud/ghost/dino/duck/rocket"),
                            "color",
                            prop("string", "可选十六进制配色，如 #2d5d9c"),
                            "workspace_id",
                            prop("string", "可选：绑定的项目工作区 ID")),
                    List.of("name", "role_summary", "system_prompt")),
            tool(
                    "update_bot",
                    "更新某个 Bot 的名称、职责、人设指令、工作区或审批模式（直接生效）",
                    map(
                            "bot", prop("string", "目标 Bot 名称或 ID，留空表示自己"),
                            "name", prop("string", "可选新名称"),
                            "role_summary", prop("string", "可选新职责描述"),
                            "system_prompt", prop("string", "可选新人设指令"),
                            "workspace_id", prop("string", "可选新工作区 ID，传空字符串解除绑定"),
                            "approval_mode", prop("string", "可选审批模式：MANUAL / SMART / FULL_ACCESS")),
                    List.of()),
            tool(
                    "archive_bot",
                    "归档移除一位专职 Bot（主 Bot 不可归档）",
                    map("bot", prop("string", "要归档的 Bot 名称或 ID")),
                    List.of("bot")),
            tool(
                    "save_playbook",
                    "把做成的做法保存或更新为当前 Bot 的标准做法（SOP），保存后立即生效并写入系统提示词",
                    map(
                            "id", prop("string", "可选：更新已有做法时传入其 ID，新建留空"),
                            "name", prop("string", "做法名称（1-60 字）"),
                            "when_to_use", prop("string", "何时使用该做法"),
                            "steps", prop("string", "清晰的操作步骤"),
                            "notes", prop("string", "注意事项与边界条件")),
                    List.of("name", "steps")),
            tool("delete_playbook", "删除当前 Bot 名下的一条做法（SOP）", map("id", prop("string", "做法 ID")), List.of("id")),
            tool(
                    "set_model",
                    "切换聊天或后台任务使用的模型配置 ID（传 default 或空表示恢复继承主模型）",
                    map(
                            "target", prop("string", "chat（聊天模型）或 task（后台任务模型）"),
                            "model_profile_id", prop("string", "模型配置 ID 或名称"),
                            "bot", prop("string", "可选：主 Bot 可指定帮其他 Bot 切换模型")),
                    List.of("target", "model_profile_id")),
            tool(
                    "search_history",
                    "按关键词检索当前 Bot 的完整历史对话记录，返回匹配的 seq 编号与摘录",
                    map(
                            "query", prop("string", "检索关键词"),
                            "limit", prop("integer", "最多返回条数（1-20）")),
                    List.of("query")),
            tool(
                    "read_history",
                    "读取当前 Bot 某条历史消息 seq 前后的完整原文",
                    map(
                            "around_seq", prop("integer", "目标消息序号 seq"),
                            "window", prop("integer", "前后各取几条（1-10）")),
                    List.of("around_seq")),
            tool("list_library_files", "列出当前 Bot 在本地资料库中产出过的文件", Map.of(), List.of()),
            tool("list_apps", "列出当前 Bot 已生成的交互小程序（名称、ID、版本）", Map.of(), List.of()),
            tool("list_routines", "列出当前 Bot 的所有例行任务（ID、频率、启停与下次执行时间）", Map.of(), List.of()),
            tool(
                    "create_routine",
                    "为当前 Bot 创建一个按计划自动运行的例行任务（到期会以该 Bot 的后台任务运行并把结论发回聊天）",
                    map(
                            "name", prop("string", "例行任务名称（1-80 字）"),
                            "instruction", prop("string", "每次到期执行的详细任务指令"),
                            "schedule_kind", prop("string", "频率：HOURLY / DAILY / WEEKDAYS / WEEKLY / CUSTOM / MANUAL"),
                            "schedule_time", prop("string", "时间 HH:mm（如 09:00；HOURLY 仅取分钟）"),
                            "schedule_weekday", prop("integer", "仅 WEEKLY 需要：0 周日 到 6 周六"),
                            "cron", prop("string", "仅 CUSTOM 需要：5 段 cron（分 时 日 月 周）"),
                            "timezone", prop("string", "可选 IANA 时区，如 Asia/Shanghai，留空随系统"),
                            "playbook_id", prop("string", "可选：按指定做法 ID 执行"),
                            "model_profile_id", prop("string", "可选：单独指定模型配置 ID")),
                    List.of("name", "instruction", "schedule_kind")),
            tool(
                    "toggle_routine",
                    "启用或暂停当前 Bot 的某条例行任务",
                    map(
                            "id", prop("string", "例行任务 ID"),
                            "enabled", prop("boolean", "true 启用，false 暂停")),
                    List.of("id", "enabled")),
            tool("delete_routine", "删除当前 Bot 的某条例行任务", map("id", prop("string", "例行任务 ID")), List.of("id")),
            tool(
                    "set_routine_flow",
                    "把当前 Bot 的某条例行任务绑定到指定做法（SOP），传空 playbook_id 表示取消绑定",
                    map(
                            "id", prop("string", "例行任务 ID"),
                            "playbook_id", prop("string", "做法 ID，留空则解绑")),
                    List.of("id")),
            tool(
                    "remember",
                    "记住一条长期有用的事实：scope=user 为关于用户本人且全团队共享的偏好/信息；scope=bot 为只与你职责相关的长期记忆",
                    map(
                            "scope", prop("string", "user（所有 Bot 共享）或 bot（仅自己）"),
                            "text", prop("string", "精炼的记忆内容（1–500 字）")),
                    List.of("scope", "text")),
            tool("forget", "删除一条过时或错误的长期记忆（传入记忆前括号中的 ID）", map("id", prop("string", "记忆 ID")), List.of("id")),
            tool(
                    "stop_task",
                    "立即停止你发起的一个正在运行或排队中的后台任务",
                    map("task_id", prop("string", "要停止的后台任务 ID")),
                    List.of("task_id")),
            tool(
                    "update_my_name",
                    "修改你自己在侧边栏显示的名称",
                    map("name", prop("string", "新名称（1–30 字，不含空格或 @）")),
                    List.of("name")),
            tool(
                    "update_my_instructions",
                    "更新你自己的角色指令/职责说明（完整替换）",
                    map("instructions", prop("string", "新的角色职责与工作规范（1–2000 字）")),
                    List.of("instructions")),
            tool("list_bot_levels", "查看团队所有 Bot（含自己）的当前等级、段位与经验值排名", Map.of(), List.of()),
            tool(
                    "install_skill",
                    "扫描 GitHub 仓库/目录链接或本机文件夹中的 SKILL.md 技能包，在聊天中弹出一张待用户勾选的安全确认卡（用户点「装上」后才真正安装）",
                    map(
                            "source", prop("string", "GitHub 仓库/目录 HTTPS 链接，或本机技能文件夹/压缩包的完整路径"),
                            "bot", prop("string", "可选：主 Bot 可指定帮其他 Bot 安装（填名称或 ID），普通 Bot 留空表示自己")),
                    List.of("source")),
            tool(
                    "remove_skill",
                    "卸载已安装的技能包（可一次卸多个；仅在用户明确要求时调用）",
                    map(
                            "names",
                            Map.of(
                                    "type",
                                    "array",
                                    "items",
                                    Map.of("type", "string"),
                                    "description",
                                    "要卸载的技能名称列表"),
                            "bot",
                            prop("string", "可选：主 Bot 可指定卸载其他 Bot 的技能（填名称或 ID）")),
                    List.of("names")));

    private final String botId;
    private final Actions actions;

    public BotChatTools(String botId, Actions actions) {
        this.botId = botId;
        this.actions = actions;
    }

    @Override
    public String workspace() {
        return "";
    }

    @Override
    public List<ToolDefinition> definitions() {
        return DEFINITIONS;
    }

    @Override
    public Result execute(ToolCall call, ModelProvider.Cancellation cancellation) throws Exception {
        cancellation.check();
        JsonNode args = parse(call.arguments());
        try {
            return switch (call.name()) {
                case "start_task" -> startTask(args);
                case "delegate_to_bot" -> delegateToBot(args);
                case "list_bots" -> listBots();
                case "create_bot" -> createBot(args);
                case "update_bot" -> updateBot(args);
                case "archive_bot" -> archiveBot(args);
                case "save_playbook" -> savePlaybook(args);
                case "delete_playbook" -> deletePlaybook(args);
                case "set_model" -> setModel(args);
                case "search_history" -> searchHistory(args);
                case "read_history" -> readHistory(args);
                case "list_library_files" -> listLibraryFiles();
                case "list_apps" -> listApps();
                case "list_routines" -> listRoutines();
                case "create_routine" -> createRoutine(args);
                case "toggle_routine" -> toggleRoutine(args);
                case "delete_routine" -> deleteRoutine(args);
                case "set_routine_flow" -> setRoutineFlow(args);
                case "remember" -> remember(args);
                case "forget" -> forget(args);
                case "stop_task" -> stopTask(args);
                case "update_my_name" -> updateMyName(args);
                case "update_my_instructions" -> updateMyInstructions(args);
                case "list_bot_levels" -> new Result(true, actions.listBotLevelsFromTool());
                case "install_skill" -> installSkill(args);
                case "remove_skill" -> removeSkill(args);
                default -> new Result(false, "未知工具：" + call.name());
            };
        } catch (Exception error) {
            return new Result(false, error.getMessage() == null ? "工具执行失败" : error.getMessage());
        }
    }

    private Result startTask(JsonNode args) {
        String title = args.path("title").asText("").strip();
        String instruction = args.path("instruction").asText("").strip();
        if (title.isEmpty() || instruction.isEmpty()) return new Result(false, "title 与 instruction 不能为空");
        Task task = actions.startTask(
                botId,
                botId,
                title,
                instruction,
                args.path("playbook_id").asText("").strip(),
                args.path("model_profile_id").asText("").strip(),
                args.path("mini_app_id").asText("").strip(),
                false);
        return new Result(true, "已启动后台任务「" + task.title() + "」（task_id=" + task.id() + "），完成后结论会自动回到本聊天。");
    }

    private Result delegateToBot(JsonNode args) {
        String target = args.path("bot").asText("").strip();
        String title = args.path("title").asText("").strip();
        String instruction = args.path("instruction").asText("").strip();
        if (target.isEmpty() || title.isEmpty() || instruction.isEmpty()) {
            return new Result(false, "bot、title 与 instruction 不能为空");
        }
        Bot resolved = actions.resolveBot(target);
        boolean silent = !resolved.id().equals(botId);
        Task task = actions.startTask(
                resolved.id(),
                botId,
                title,
                instruction,
                args.path("playbook_id").asText("").strip(),
                "",
                silent);
        return new Result(
                true,
                "已将任务「" + task.title() + "」委派给 [" + resolved.name() + "](bot:" + resolved.id() + ")（task_id="
                        + task.id() + "），完成后会自动把结论回传给你汇总。");
    }

    private Result listBots() {
        var sb = new StringBuilder();
        for (Bot bot : actions.listBots()) {
            sb.append("- [")
                    .append(bot.name())
                    .append("](bot:")
                    .append(bot.id())
                    .append(") ")
                    .append(bot.main() ? "[主Bot] " : "")
                    .append(bot.roleSummary())
                    .append('\n');
        }
        return new Result(true, sb.isEmpty() ? "暂无 Bot" : sb.toString());
    }

    private Result createBot(JsonNode args) {
        Bot created = actions.createBot(
                args.path("name").asText("").strip(),
                args.path("role_summary").asText("").strip(),
                args.path("system_prompt").asText("").strip(),
                args.path("shape").asText("").strip(),
                args.path("color").asText("").strip(),
                args.path("workspace_id").asText("").strip());
        return new Result(
                true, "已创建新 Bot：[" + created.name() + "](bot:" + created.id() + ")，职责：" + created.roleSummary());
    }

    private Result updateBot(JsonNode args) {
        String target = args.path("bot").asText("").strip();
        Bot updated = actions.updateBotFromTool(
                target.isEmpty() ? botId : target,
                textOrNull(args, "name"),
                textOrNull(args, "role_summary"),
                textOrNull(args, "system_prompt"),
                textOrNull(args, "workspace_id"),
                textOrNull(args, "approval_mode"));
        return new Result(true, "已更新 Bot：[" + updated.name() + "](bot:" + updated.id() + ")");
    }

    private Result archiveBot(JsonNode args) {
        String target = args.path("bot").asText("").strip();
        if (target.isEmpty()) return new Result(false, "请指定要归档的 Bot");
        actions.archiveBotFromTool(target);
        return new Result(true, "已归档 Bot：" + target);
    }

    private Result savePlaybook(JsonNode args) {
        Playbook saved = actions.savePlaybookFromTool(
                botId,
                args.path("id").asText("").strip(),
                args.path("name").asText("").strip(),
                args.path("when_to_use").asText("").strip(),
                args.path("steps").asText("").strip(),
                args.path("notes").asText("").strip());
        return new Result(true, "已保存做法「" + saved.name() + "」（playbook_id=" + saved.id() + "），已立即生效。");
    }

    private Result deletePlaybook(JsonNode args) {
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) return new Result(false, "缺少做法 id");
        actions.deletePlaybookFromTool(botId, id);
        return new Result(true, "已删除做法：" + id);
    }

    private Result setModel(JsonNode args) {
        String scope = args.path("target").asText("").strip();
        String profile = args.path("model_profile_id").asText("").strip();
        String targetBot = args.path("bot").asText("").strip();
        String message = actions.setModelFromTool(botId, targetBot.isEmpty() ? botId : targetBot, scope, profile);
        return new Result(true, message);
    }

    private Result searchHistory(JsonNode args) {
        String query = args.path("query").asText("").strip();
        int limit = args.path("limit").asInt(10);
        List<Entry> hits = actions.searchHistory(botId, query, limit);
        if (hits.isEmpty()) return new Result(true, "未找到匹配的历史记录");
        var sb = new StringBuilder();
        for (Entry hit : hits) {
            String snippet = hit.content().replaceAll("\\s+", " ");
            if (snippet.length() > 160) snippet = snippet.substring(0, 160) + "…";
            sb.append('#')
                    .append(hit.seq())
                    .append(" [")
                    .append(hit.kind())
                    .append("] ")
                    .append(snippet)
                    .append('\n');
        }
        return new Result(true, sb.toString());
    }

    private Result readHistory(JsonNode args) {
        long seq = args.path("around_seq").asLong(0);
        int window = Math.max(1, Math.min(args.path("window").asInt(4), 10));
        if (seq <= 0) return new Result(false, "around_seq 必须大于 0");
        List<Entry> entries = actions.readHistory(botId, seq, window);
        if (entries.isEmpty()) return new Result(true, "该序号附近没有记录");
        var sb = new StringBuilder();
        for (Entry entry : entries) {
            sb.append('#')
                    .append(entry.seq())
                    .append(" [")
                    .append(entry.kind())
                    .append("]\n")
                    .append(entry.content())
                    .append("\n\n");
        }
        return new Result(true, sb.toString());
    }

    private Result listLibraryFiles() {
        List<BotFile> files = actions.listLibraryFiles(botId);
        if (files.isEmpty()) return new Result(true, "当前 Bot 暂未在资料库写入文件");
        var sb = new StringBuilder();
        for (BotFile file : files) {
            sb.append("- ")
                    .append(file.relativePath())
                    .append("（")
                    .append(file.sizeBytes())
                    .append(" bytes）\n");
        }
        return new Result(true, sb.toString());
    }

    private Result listApps() {
        List<MiniApp> apps = actions.listAppsFromTool(botId);
        if (apps.isEmpty()) return new Result(true, "当前 Bot 暂未生成小程序");
        var sb = new StringBuilder();
        for (MiniApp app : apps) {
            sb.append("- [")
                    .append(app.name())
                    .append("](app:")
                    .append(app.id())
                    .append(") v")
                    .append(app.version());
            if (!app.description().isBlank()) sb.append("：").append(app.description());
            sb.append('\n');
        }
        return new Result(true, sb.toString());
    }

    private Result listRoutines() {
        List<Routine> routines = actions.listRoutinesFromTool(botId);
        if (routines.isEmpty()) return new Result(true, "当前 Bot 暂未设置例行任务");
        var sb = new StringBuilder();
        for (Routine r : routines) {
            sb.append("- ")
                    .append(r.name())
                    .append("（id=")
                    .append(r.id())
                    .append("，")
                    .append(r.scheduleKind())
                    .append(' ')
                    .append(r.cron().isBlank() ? r.scheduleTime() : r.cron())
                    .append("，")
                    .append(r.enabled() ? "启用中" : "已暂停")
                    .append("）\n");
        }
        return new Result(true, sb.toString());
    }

    private Result createRoutine(JsonNode args) {
        Routine created = actions.createRoutineFromTool(
                botId,
                args.path("name").asText("").strip(),
                args.path("instruction").asText("").strip(),
                args.path("schedule_kind").asText("DAILY").strip(),
                args.path("schedule_time").asText("09:00").strip(),
                args.has("schedule_weekday") && !args.get("schedule_weekday").isNull()
                        ? args.get("schedule_weekday").asInt()
                        : null,
                args.path("cron").asText("").strip(),
                args.path("timezone").asText("").strip(),
                args.path("playbook_id").asText("").strip(),
                args.path("model_profile_id").asText("").strip());
        return new Result(
                true,
                "已创建例行任务「" + created.name() + "」（id=" + created.id() + "，cron="
                        + (created.cron().isBlank() ? "手动" : created.cron()) + "）。");
    }

    private Result toggleRoutine(JsonNode args) {
        String id = args.path("id").asText("").strip();
        boolean enabled = args.path("enabled").asBoolean(true);
        Routine updated = actions.toggleRoutineFromTool(botId, id, enabled);
        return new Result(true, "已" + (updated.enabled() ? "启用" : "暂停") + "例行任务「" + updated.name() + "」。");
    }

    private Result deleteRoutine(JsonNode args) {
        String id = args.path("id").asText("").strip();
        actions.deleteRoutineFromTool(botId, id);
        return new Result(true, "已删除例行任务：" + id);
    }

    private Result setRoutineFlow(JsonNode args) {
        String id = args.path("id").asText("").strip();
        String playbookId = args.path("playbook_id").asText("").strip();
        Routine updated = actions.setRoutinePlaybookFromTool(botId, id, playbookId);
        return new Result(
                true,
                playbookId.isEmpty()
                        ? "已取消例行任务「" + updated.name() + "」的做法绑定。"
                        : "已将例行任务「" + updated.name() + "」绑定到做法 " + playbookId + "。");
    }

    private Result remember(JsonNode args) {
        String scope = args.path("scope").asText("bot").strip();
        String text = args.path("text").asText("").strip();
        if (text.isEmpty()) return new Result(false, "记忆内容不能为空");
        Fact saved = actions.rememberFromTool(botId, scope, text);
        return new Result(
                true,
                "已记住（id=" + saved.id() + "，范围=" + ("USER".equals(saved.scope()) ? "用户全局" : "Bot 专属") + "）："
                        + saved.content());
    }

    private Result forget(JsonNode args) {
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) return new Result(false, "请提供要遗忘的记忆 id");
        actions.forgetFromTool(botId, id);
        return new Result(true, "已遗忘记忆：" + id);
    }

    private Result stopTask(JsonNode args) {
        String taskId = args.path("task_id").asText("").strip();
        if (taskId.isEmpty()) return new Result(false, "请提供要停止的 task_id");
        Task stopped = actions.stopTaskFromTool(botId, taskId);
        return new Result(true, "已停止后台任务「" + stopped.title() + "」（状态=" + stopped.status() + "）。");
    }

    private Result updateMyName(JsonNode args) {
        String name = args.path("name").asText("").strip();
        if (name.isEmpty()) return new Result(false, "name 不能为空");
        Bot updated = actions.updateBotFromTool(botId, name, null, null, null, null);
        return new Result(true, "已将名称更新为：[" + updated.name() + "](bot:" + updated.id() + ")");
    }

    private Result updateMyInstructions(JsonNode args) {
        String instructions = args.path("instructions").asText("").strip();
        if (instructions.isEmpty()) return new Result(false, "instructions 不能为空");
        actions.updateBotFromTool(botId, null, null, instructions, null, null);
        return new Result(true, "已更新我的角色职责说明。");
    }

    private Result installSkill(JsonNode args) {
        String source = args.path("source").asText("").strip();
        String target = args.path("bot").asText("").strip();
        if (source.isEmpty()) return new Result(false, "source 不能为空");
        return new Result(true, actions.installSkillFromTool(botId, target, source));
    }

    private Result removeSkill(JsonNode args) {
        JsonNode arr = args.path("names");
        var names = new java.util.ArrayList<String>();
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                if (item.isTextual() && !item.asText().isBlank()) names.add(item.asText().strip());
            }
        }
        if (names.isEmpty()) return new Result(false, "names 不能为空");
        String target = args.path("bot").asText("").strip();
        return new Result(true, actions.removeSkillFromTool(botId, target, names));
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private static JsonNode parse(String raw) throws IOException {
        if (raw == null || raw.isBlank()) return JSON.createObjectNode();
        return JSON.readTree(raw);
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> map(Object... kv) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            result.put((String) kv[i], kv[i + 1]);
        }
        return result;
    }

    private static ToolDefinition tool(
            String name, String description, Map<String, Object> properties, List<String> required) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return new ToolDefinition(name, description, schema);
    }
}
