package dev.javaharness.server.service;

import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Bot;
import dev.javaharness.adapters.bot.BotStore.BotChange;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.adapters.model.JtokkitTokenCounter;
import dev.javaharness.adapters.settings.HarnessSettings;
import dev.javaharness.adapters.settings.ModelProfile;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStep;
import dev.javaharness.spi.session.SessionSnapshot;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.tool.ToolExecution;
import dev.javaharness.spi.workspace.Workspace;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bot 调试观测服务（支撑灵动岛 Debug Mode）：
 * 1. Context：聚合当前 Bot 系统提示词分段、Token 构成估算、最近实测输入/缓存、聊天记录范围与挂载工具定义；
 * 2. Traces：聚合最近聊天与后台任务运行的分步时间轴、每步工具参数/返回值、耗时与实测 Token 跳变；
 * 3. Changes：返回 Bot 的自我修改、记忆写入、做法与例行变更审计日志。
 */
public final class BotDebugService {
    private static final int COMPACT_THRESHOLD = 36;
    private static final int COMPACT_KEEP_RECENT = 14;

    public record PromptSectionView(String heading, String text, int estimatedTokens) {}

    public record TranscriptMessageView(long seq, String kind, String text) {}

    public record ToolSummaryView(String name, String description) {}

    public record MeasuredUsageView(int inputTokens, int cachedTokens, int outputTokens) {}

    public record ContextView(
            String model,
            int windowTokens,
            int systemTokens,
            int transcriptTokens,
            int toolsTokens,
            int totalEstimatedTokens,
            MeasuredUsageView measured,
            List<PromptSectionView> sections,
            long transcriptFromSeq,
            long transcriptToSeq,
            Map<String, Integer> transcriptCounts,
            int compactAbove,
            int keepRecent,
            List<TranscriptMessageView> transcriptMessages,
            List<ToolSummaryView> tools) {}

    public record TraceCallView(
            String id,
            String name,
            String arguments,
            String status,
            String output,
            int outputChars,
            boolean error) {}

    public record TraceStepView(
            int step,
            Instant startedAt,
            Instant endedAt,
            Integer inputTokens,
            Integer cachedTokens,
            Integer outputTokens,
            String text,
            String reasoning,
            List<TraceCallView> calls) {}

    public record TraceView(
            String id,
            String kind,
            String title,
            String runId,
            String status,
            String model,
            Instant startedAt,
            Instant endedAt,
            String error,
            int maxSteps,
            int totalInputTokens,
            int totalCachedTokens,
            int totalOutputTokens,
            List<TraceStepView> steps) {}

    public record UsageTotals(long inputTokens, long outputTokens, long cachedTokens, long calls) {
        static final UsageTotals ZERO = new UsageTotals(0, 0, 0, 0);

        UsageTotals plus(long in, long out, long cached, long c) {
            return new UsageTotals(inputTokens + in, outputTokens + out, cachedTokens + cached, calls + c);
        }
    }

    public record BotUsageRow(
            String botId,
            String botName,
            String shape,
            String color,
            String avatarUrl,
            UsageTotals totals,
            UsageTotals chat,
            UsageTotals task,
            UsageTotals routine) {}

    public record BotUsageReport(
            int days, UsageTotals totals, Map<String, UsageTotals> bySource, List<BotUsageRow> bots) {}

    private record StepUsageRow(int step, Integer inputTokens, Integer cachedTokens, Integer outputTokens) {}

    private final BotStore store;
    private final SessionStore sessions;
    private final SettingsService settings;
    private final JdbcTemplate jdbc;

    public BotDebugService(BotStore store, SessionStore sessions, SettingsService settings, JdbcTemplate jdbc) {
        this.store = store;
        this.sessions = sessions;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    public ContextView context(String botId) {
        Bot bot = store.getBot(botId);
        HarnessSettings currentSettings = settings.current();
        String modelName = resolveChatModelName(bot, currentSettings);
        int windowTokens = resolveChatWindowTokens(bot, currentSettings);
        TokenCounter counter = new JtokkitTokenCounter(modelName.isBlank() ? "gpt-4o" : modelName);

        var roster = store.listBots(false);
        var playbooks = store.playbooks(bot.id());
        var skills = bot.main() ? store.allSkills() : store.skillsForBot(bot.id());
        var routines = store.routines(bot.id());
        var userFacts = store.facts("USER", "");
        var botFacts = store.facts("BOT", bot.id());
        var apps = store.miniApps(bot.id());
        var activeTasks = store.activeTasksForBot(bot.id());
        var meta = store.ensureMeta();
        String workspaceName = workspaceName(bot.workspaceId());

        List<BotPrompts.PromptSection> rawSections = BotPrompts.chatSections(
                bot,
                meta.userDisplayName(),
                roster,
                playbooks,
                skills,
                routines,
                userFacts,
                botFacts,
                apps,
                activeTasks,
                workspaceName);
        var sectionViews = new ArrayList<PromptSectionView>(rawSections.size());
        int systemTokens = 0;
        for (BotPrompts.PromptSection s : rawSections) {
            int est = counter.count(s.text());
            systemTokens += est;
            sectionViews.add(new PromptSectionView(s.heading(), s.text(), est));
        }

        var summary = store.summary(bot.id());
        List<Entry> recentEntries = summary != null
                ? store.entriesAfter(bot.id(), summary.throughSeq())
                : store.entries(bot.id(), 80);
        String transcriptBlock = BotPrompts.chatContext(summary, recentEntries, "", counter, Math.max(2048, windowTokens / 2));
        int transcriptTokens = transcriptBlock.isBlank() ? 0 : counter.count(transcriptBlock);

        var counts = new LinkedHashMap<String, Integer>();
        var previewMessages = new ArrayList<TranscriptMessageView>();
        long fromSeq = recentEntries.isEmpty() ? 0 : recentEntries.getFirst().seq();
        long toSeq = recentEntries.isEmpty() ? 0 : recentEntries.getLast().seq();
        for (Entry e : recentEntries) {
            counts.merge(e.kind(), 1, Integer::sum);
            String snippet = e.content();
            if (snippet.length() > 600) snippet = snippet.substring(0, 600) + "…";
            previewMessages.add(new TranscriptMessageView(e.seq(), e.kind(), snippet));
        }
        if (previewMessages.size() > 30) {
            previewMessages = new ArrayList<>(previewMessages.subList(previewMessages.size() - 30, previewMessages.size()));
        }

        List<ToolDefinition> chatTools = new BotChatTools(bot.id(), null).definitions();
        var toolViews = new ArrayList<ToolSummaryView>(chatTools.size());
        var toolText = new StringBuilder();
        for (ToolDefinition def : chatTools) {
            toolViews.add(new ToolSummaryView(def.name(), def.description()));
            toolText.append(def.name()).append(':').append(def.description()).append('\n');
        }
        int toolsTokens = counter.count(toolText.toString()) + chatTools.size() * 28;

        MeasuredUsageView measured = latestMeasuredUsage(bot.chatSessionId());
        int totalEstimated = systemTokens + transcriptTokens + toolsTokens;
        return new ContextView(
                modelName.isBlank() ? "未配置模型" : modelName,
                windowTokens,
                systemTokens,
                transcriptTokens,
                toolsTokens,
                totalEstimated,
                measured,
                List.copyOf(sectionViews),
                fromSeq,
                toSeq,
                counts,
                COMPACT_THRESHOLD,
                COMPACT_KEEP_RECENT,
                List.copyOf(previewMessages),
                List.copyOf(toolViews));
    }

    public List<TraceView> traces(String botId) {
        Bot bot = store.getBot(botId);
        var result = new ArrayList<TraceView>();

        try {
            SessionSnapshot chatSnap = sessions.snapshot(bot.chatSessionId());
            List<Run> chatRuns = chatSnap.runs();
            int start = Math.max(0, chatRuns.size() - 5);
            for (int i = chatRuns.size() - 1; i >= start; i--) {
                Run run = chatRuns.get(i);
                result.add(buildTrace(bot.id() + ":chat:" + run.id(), "CHAT", "聊天轮次", run));
            }
        } catch (HarnessException ignored) {
            // Chat session may not exist yet.
        }

        List<Task> tasks = store.tasksForBot(bot.id(), 8);
        for (Task task : tasks) {
            if (task.runId().isBlank()) continue;
            try {
                Run run = sessions.run(task.runId());
                result.add(buildTrace(task.id(), "TASK", task.title(), run));
            } catch (HarnessException ignored) {
                // Run not persisted yet.
            }
        }

        result.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return List.copyOf(result);
    }

    public List<BotChange> changes(String botId) {
        store.getBot(botId);
        return store.changesForBot(botId, 60);
    }

    private TraceView buildTrace(String id, String kind, String title, Run run) {
        List<RunStep> steps = sessions.steps(run.id());
        List<ToolExecution> tools = sessions.tools(run.id());
        List<ModelRequest> reqs = sessions.modelRequests(run.id());
        Map<Integer, StepUsageRow> usageByStep = stepUsages(run.id());

        Map<Integer, List<ToolExecution>> toolsByStep = new HashMap<>();
        for (ToolExecution tool : tools) {
            toolsByStep.computeIfAbsent(tool.step(), k -> new ArrayList<>()).add(tool);
        }

        var stepViews = new ArrayList<TraceStepView>(steps.size());
        int totalIn = 0;
        int totalCached = 0;
        int totalOut = 0;
        String fullOutput = run.output() == null ? "" : run.output();
        for (int i = 0; i < steps.size(); i++) {
            RunStep s = steps.get(i);
            int startIdx = Math.max(0, Math.min(s.outputStart(), fullOutput.length()));
            int endIdx = i + 1 < steps.size()
                    ? Math.max(startIdx, Math.min(steps.get(i + 1).outputStart(), fullOutput.length()))
                    : fullOutput.length();
            String stepText = fullOutput.substring(startIdx, endIdx).strip();

            StepUsageRow u = usageByStep.get(s.step());
            if (u != null) {
                if (u.inputTokens() != null) totalIn += u.inputTokens();
                if (u.cachedTokens() != null) totalCached += u.cachedTokens();
                if (u.outputTokens() != null) totalOut += u.outputTokens();
            }

            List<ToolExecution> stepTools = toolsByStep.getOrDefault(s.step(), List.of());
            var calls = new ArrayList<TraceCallView>(stepTools.size());
            for (ToolExecution te : stepTools) {
                String out = te.output() == null ? "" : te.output();
                boolean isErr = "FAILED".equalsIgnoreCase(te.status()) || "ERROR".equalsIgnoreCase(te.status());
                calls.add(new TraceCallView(
                        te.callId(),
                        te.name(),
                        te.arguments() == null ? "" : te.arguments(),
                        te.status(),
                        out.length() > 1600 ? out.substring(0, 1600) + "…" : out,
                        out.length(),
                        isErr));
            }
            stepViews.add(new TraceStepView(
                    s.step(),
                    s.startedAt(),
                    s.finishedAt(),
                    u == null ? null : u.inputTokens(),
                    u == null ? null : u.cachedTokens(),
                    u == null ? null : u.outputTokens(),
                    stepText,
                    s.reasoning() == null ? "" : s.reasoning(),
                    List.copyOf(calls)));
        }

        Instant endedAt = null;
        if (!steps.isEmpty() && steps.getLast().finishedAt() != null) {
            endedAt = steps.getLast().finishedAt();
        } else if (!run.status().active()) {
            endedAt = run.createdAt();
        }
        String err = (run.status().name().equals("FAILED") || run.status().name().equals("CANCELLED"))
                ? (run.detail() == null ? "" : run.detail())
                : "";
        int maxSteps = Math.max(steps.size(), Math.max(reqs.size(), 12));
        return new TraceView(
                id,
                kind,
                title,
                run.id(),
                run.status().name(),
                run.model(),
                run.createdAt(),
                endedAt,
                err,
                maxSteps,
                totalIn,
                totalCached,
                totalOut,
                List.copyOf(stepViews));
    }

    private MeasuredUsageView latestMeasuredUsage(String chatSessionId) {
        try {
            SessionSnapshot snap = sessions.snapshot(chatSessionId);
            for (int i = snap.runs().size() - 1; i >= 0; i--) {
                Run run = snap.runs().get(i);
                var usages = stepUsages(run.id());
                if (!usages.isEmpty()) {
                    StepUsageRow last = usages.values().stream()
                            .filter(u -> u.inputTokens() != null)
                            .reduce((a, b) -> a.step() >= b.step() ? a : b)
                            .orElse(null);
                    if (last != null) {
                        return new MeasuredUsageView(
                                last.inputTokens(),
                                last.cachedTokens() == null ? 0 : last.cachedTokens(),
                                last.outputTokens() == null ? 0 : last.outputTokens());
                    }
                }
            }
        } catch (HarnessException ignored) {
            // No runs yet.
        }
        return null;
    }

    private Map<Integer, StepUsageRow> stepUsages(String runId) {
        var map = new HashMap<Integer, StepUsageRow>();
        jdbc.query(
                "SELECT step, input_tokens, cached_tokens, output_tokens FROM harness_step WHERE run_id=? ORDER BY step ASC",
                rs -> {
                    int step = rs.getInt("step");
                    Integer in = rs.getObject("input_tokens", Integer.class);
                    Integer cached = rs.getObject("cached_tokens", Integer.class);
                    Integer out = rs.getObject("output_tokens", Integer.class);
                    map.put(step, new StepUsageRow(step, in, cached, out));
                },
                runId);
        return map;
    }

    private String resolveChatModelName(Bot bot, HarnessSettings current) {
        if (!bot.chatProfileId().isBlank()) {
            try {
                ModelProfile profile = settings.profile(bot.chatProfileId());
                if (profile != null
                        && profile.settings() != null
                        && !profile.settings().modelName().isBlank()) {
                    return profile.settings().modelName();
                }
            } catch (HarnessException ignored) {
                // Fallback to default.
            }
        }
        return current == null ? "" : current.modelName();
    }

    private int resolveChatWindowTokens(Bot bot, HarnessSettings current) {
        if (!bot.chatProfileId().isBlank()) {
            try {
                ModelProfile profile = settings.profile(bot.chatProfileId());
                if (profile != null
                        && profile.settings() != null
                        && profile.settings().contextWindowTokens() > 0) {
                    return profile.settings().contextWindowTokens();
                }
            } catch (HarnessException ignored) {
                // Fallback to default.
            }
        }
        return current != null && current.contextWindowTokens() > 0 ? current.contextWindowTokens() : 64_000;
    }

    private String workspaceName(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) return "";
        try {
            Workspace ws = sessions.workspace(workspaceId);
            return ws == null ? "" : ws.name();
        } catch (HarnessException ignored) {
            return "";
        }
    }

    /** 按 Bot 及来源（聊天 / 后台任务 / 例行任务）统计最近 N 天（0 表示全部）的 Token 消耗。 */
    public BotUsageReport usage(int days) {
        if (days < 0 || days > 366) throw new HarnessException("INVALID", "统计天数需在 0–366 之间");
        Instant since = days == 0
                ? Instant.EPOCH
                : LocalDate.now(ZoneId.systemDefault())
                        .minusDays(days - 1L)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant();
        OffsetDateTime sinceParam = OffsetDateTime.ofInstant(since, ZoneOffset.UTC);

        Map<String, Bot> botsById = new LinkedHashMap<>();
        for (Bot b : store.listBots(true)) botsById.put(b.id(), b);

        Map<String, UsageTotals> chatByBot = new HashMap<>();
        Map<String, UsageTotals> taskByBot = new HashMap<>();
        Map<String, UsageTotals> routineByBot = new HashMap<>();

        // Chat turns usage
        jdbc.query(
                """
                SELECT b.id AS bot_id,
                       COALESCE(SUM(l.input_tokens), 0) AS in_tok,
                       COALESCE(SUM(l.output_tokens), 0) AS out_tok,
                       COALESCE(SUM(l.cached_tokens), 0) AS cached_tok,
                       COUNT(*) AS calls
                FROM harness_usage_ledger l
                JOIN harness_run r ON r.id = l.run_id
                JOIN harness_bot b ON b.chat_session_id = r.session_id
                WHERE l.recorded_at >= ?
                GROUP BY b.id
                """,
                rs -> {
                    String botId = rs.getString("bot_id");
                    chatByBot.put(
                            botId,
                            new UsageTotals(
                                    rs.getLong("in_tok"),
                                    rs.getLong("out_tok"),
                                    rs.getLong("cached_tok"),
                                    rs.getLong("calls")));
                },
                sinceParam);

        // Background tasks & routines usage
        jdbc.query(
                """
                SELECT t.bot_id AS bot_id,
                       CASE WHEN t.routine_id IS NOT NULL AND t.routine_id <> '' THEN 'routine' ELSE 'task' END AS src,
                       COALESCE(SUM(l.input_tokens), 0) AS in_tok,
                       COALESCE(SUM(l.output_tokens), 0) AS out_tok,
                       COALESCE(SUM(l.cached_tokens), 0) AS cached_tok,
                       COUNT(*) AS calls
                FROM harness_usage_ledger l
                JOIN harness_run r ON r.id = l.run_id
                JOIN harness_bot_task t ON t.session_id = r.session_id
                WHERE l.recorded_at >= ?
                GROUP BY t.bot_id, CASE WHEN t.routine_id IS NOT NULL AND t.routine_id <> '' THEN 'routine' ELSE 'task' END
                """,
                rs -> {
                    String botId = rs.getString("bot_id");
                    String src = rs.getString("src");
                    var totals = new UsageTotals(
                            rs.getLong("in_tok"),
                            rs.getLong("out_tok"),
                            rs.getLong("cached_tok"),
                            rs.getLong("calls"));
                    if ("routine".equals(src)) routineByBot.put(botId, totals);
                    else taskByBot.put(botId, totals);
                },
                sinceParam);

        UsageTotals totalAll = UsageTotals.ZERO;
        UsageTotals totalChat = UsageTotals.ZERO;
        UsageTotals totalTask = UsageTotals.ZERO;
        UsageTotals totalRoutine = UsageTotals.ZERO;
        var rows = new ArrayList<BotUsageRow>();

        for (Bot b : botsById.values()) {
            UsageTotals c = chatByBot.getOrDefault(b.id(), UsageTotals.ZERO);
            UsageTotals t = taskByBot.getOrDefault(b.id(), UsageTotals.ZERO);
            UsageTotals rt = routineByBot.getOrDefault(b.id(), UsageTotals.ZERO);
            UsageTotals sum = UsageTotals.ZERO
                    .plus(c.inputTokens(), c.outputTokens(), c.cachedTokens(), c.calls())
                    .plus(t.inputTokens(), t.outputTokens(), t.cachedTokens(), t.calls())
                    .plus(rt.inputTokens(), rt.outputTokens(), rt.cachedTokens(), rt.calls());
            if (b.archived() && sum.calls() == 0) continue;
            totalChat = totalChat.plus(c.inputTokens(), c.outputTokens(), c.cachedTokens(), c.calls());
            totalTask = totalTask.plus(t.inputTokens(), t.outputTokens(), t.cachedTokens(), t.calls());
            totalRoutine = totalRoutine.plus(rt.inputTokens(), rt.outputTokens(), rt.cachedTokens(), rt.calls());
            totalAll = totalAll.plus(sum.inputTokens(), sum.outputTokens(), sum.cachedTokens(), sum.calls());
            String avatarUrl = b.avatarPath().isBlank() ? "" : "/api/bots/" + b.id() + "/avatar?v=" + b.revision();
            rows.add(new BotUsageRow(b.id(), b.name(), b.shape(), b.color(), avatarUrl, sum, c, t, rt));
        }
        rows.sort(Comparator.comparingLong(
                        (BotUsageRow r) -> r.totals().inputTokens() + r.totals().outputTokens())
                .reversed()
                .thenComparing(BotUsageRow::botName));

        var bySource = new LinkedHashMap<String, UsageTotals>();
        bySource.put("chat", totalChat);
        bySource.put("task", totalTask);
        bySource.put("routine", totalRoutine);
        return new BotUsageReport(days, totalAll, bySource, rows);
    }
}
