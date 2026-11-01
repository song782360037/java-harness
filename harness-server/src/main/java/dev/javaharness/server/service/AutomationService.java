package dev.javaharness.server.service;

import dev.javaharness.adapters.automation.AutomationStore;
import dev.javaharness.adapters.automation.AutomationStore.Automation;
import dev.javaharness.adapters.automation.AutomationStore.Execution;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * 定时自动化：校验与保存定义，按 cron 计算下次触发时间，到期时新建会话并提交运行。
 * 服务未运行期间错过的计划只补跑 7 天内最近的一次；上一次运行未结束时跳过本次并记录原因。
 */
public final class AutomationService implements AutoCloseable {
    public static final Set<String> KINDS = Set.of("HOURLY", "DAILY", "WEEKDAYS", "WEEKLY", "CUSTOM", "MANUAL");
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern MINUTE = Pattern.compile("[0-5]?\\d");
    private static final Duration CATCH_UP_WINDOW = Duration.ofDays(7);
    // A trigger later than this is reported as a catch-up rather than an on-time run.
    private static final Duration ON_TIME = Duration.ofMinutes(2);
    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);

    /** 新建或编辑请求；revision 新建时为 0，reasoningEffort 为 null 表示使用模型配置默认值，approvalMode 为 null 视为手动审批。 */
    public record Draft(
            long revision,
            String name,
            String prompt,
            String workspaceId,
            String modelProfileId,
            String reasoningEffort,
            String scheduleKind,
            String scheduleTime,
            int scheduleWeekday,
            String cron,
            boolean allowCommands,
            String approvalMode,
            boolean enabled) {}

    private final AutomationStore store;
    private final SessionStore sessions;
    private final AgentEngine engine;
    private final SettingsService settings;
    private final UnattendedSubmissions unattended;
    private final Clock clock;
    private final ZoneId zone;
    private ScheduledExecutorService scheduler;
    private volatile boolean closed;

    public AutomationService(
            AutomationStore store,
            SessionStore sessions,
            AgentEngine engine,
            SettingsService settings,
            UnattendedSubmissions unattended,
            Clock clock,
            ZoneId zone) {
        this.store = store;
        this.sessions = sessions;
        this.engine = engine;
        this.settings = settings;
        this.unattended = unattended;
        this.clock = clock;
        this.zone = zone;
    }

    /** Start checking due automations; the first check also handles runs missed while stopped. */
    public synchronized void start(Duration interval) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("harness-automation").daemon().factory());
        scheduler.scheduleWithFixedDelay(this::safeTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stop scheduling and wait briefly for an in-flight check, which stops before triggering anything new. */
    @Override
    public void close() {
        closed = true;
        ScheduledExecutorService current;
        synchronized (this) {
            current = scheduler;
        }
        if (current == null) return;
        current.shutdown();
        try {
            current.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized List<Automation> list() {
        return store.list();
    }

    public synchronized List<Execution> executions(String id) {
        return store.executions(id, 50);
    }

    /** Create when the id is empty; the next trigger time is recomputed from now. */
    public synchronized Automation save(String id, Draft draft) {
        String name = draft.name() == null ? "" : draft.name().strip();
        String prompt = draft.prompt() == null ? "" : draft.prompt().strip();
        if (name.isEmpty() || name.length() > 100) throw new HarnessException("INVALID", "名称需为 1–100 个字符");
        if (prompt.isEmpty() || prompt.length() > 4000) throw new HarnessException("INVALID", "提示词需为 1–4000 个字符");
        var existing = id.isEmpty() ? null : store.get(id);
        String workspaceId = draft.workspaceId() == null ? "" : draft.workspaceId();
        String profileId =
                draft.modelProfileId() == null || draft.modelProfileId().isEmpty() ? "default" : draft.modelProfileId();
        // Unchanged references are not revalidated, so an automation can still be paused after they are deleted.
        if (!workspaceId.isEmpty() && (existing == null || !workspaceId.equals(existing.workspaceId())))
            sessions.workspace(workspaceId);
        if (existing == null || !profileId.equals(existing.modelProfileId())) settings.profile(profileId);
        if (draft.reasoningEffort() != null && !ModelSelection.EFFORTS.contains(draft.reasoningEffort()))
            throw new HarnessException("INVALID", "不支持的思考强度");
        ApprovalMode approvalMode =
                draft.approvalMode() == null ? ApprovalMode.MANUAL : ApprovalMode.parse(draft.approvalMode());
        String kind = draft.scheduleKind() == null ? "" : draft.scheduleKind();
        String time = draft.scheduleTime() == null ? "09:00" : draft.scheduleTime();
        String cron = cron(kind, time, draft.scheduleWeekday(), draft.cron());
        if (!TIME.matcher(time).matches()) time = "09:00";
        Instant now = clock.instant();
        return store.save(new Automation(
                id,
                name,
                prompt,
                workspaceId,
                profileId,
                draft.reasoningEffort(),
                kind,
                time,
                draft.scheduleWeekday(),
                cron,
                draft.allowCommands(),
                approvalMode.name(),
                draft.enabled(),
                draft.enabled() ? next(cron, now) : null,
                existing == null ? now : existing.createdAt(),
                draft.revision()));
    }

    public synchronized void delete(String id, long revision) {
        store.delete(id, revision);
    }

    /** Trigger immediately without moving the schedule; rejected while the previous run is still active. */
    public synchronized Execution runNow(String id) {
        var automation = store.get(id);
        if (running(automation)) throw new HarnessException("CONFLICT", "上一次运行仍在进行，请等待完成或停止后再试");
        return trigger(automation, "MANUAL", null);
    }

    /** Check due automations once; exposed for tests. */
    public synchronized void tick() {
        Instant now = clock.instant();
        for (Automation automation : store.due(now)) {
            if (closed) return;
            try {
                check(automation, now);
            } catch (RuntimeException error) {
                log.warn("Automation {} check failed", automation.id(), error);
            }
        }
    }

    private void check(Automation automation, Instant now) {
        Instant latest = null;
        int missed = 0;
        Instant window = now.minus(CATCH_UP_WINDOW);
        boolean expired = automation.nextRunAt().isBefore(window);
        for (Instant probe = expired ? next(automation.cron(), window) : automation.nextRunAt();
                probe != null && !probe.isAfter(now);
                probe = next(automation.cron(), probe)) {
            if (latest != null) missed++;
            latest = probe;
        }
        store.schedule(automation.id(), next(automation.cron(), now));
        if (expired || missed > 0)
            store.record(
                    automation.id(),
                    "SCHEDULED",
                    automation.nextRunAt(),
                    now,
                    "SKIPPED",
                    latest == null ? "服务未运行，错过的计划时间已超过 7 天，不再补跑" : "服务未运行期间错过多次计划，仅补跑最近一次",
                    null);
        if (latest == null) return;
        if (running(automation)) {
            store.record(automation.id(), "SCHEDULED", latest, now, "SKIPPED", "上一次运行仍在进行，已跳过本次", null);
            return;
        }
        trigger(automation, latest.isBefore(now.minus(ON_TIME)) ? "CATCH_UP" : "SCHEDULED", latest);
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException error) {
            log.warn("Automation check failed", error);
        }
    }

    private boolean running(Automation automation) {
        var latest = store.latestStarted(automation.id());
        return latest != null
                && latest.runStatus() != null
                && RunStatus.valueOf(latest.runStatus()).active();
    }

    private Execution trigger(Automation automation, String trigger, Instant scheduledFor) {
        Instant now = clock.instant();
        String title = automation.name() + " · " + TITLE_TIME.format(now.atZone(zone));
        if (title.length() > 100)
            title = automation.name().substring(0, Math.min(automation.name().length(), 100));
        var selection = new ModelSelection(automation.modelProfileId(), automation.reasoningEffort());
        String sessionId;
        try {
            // Check the model first so a misconfigured automation does not leave an empty session per trigger.
            if (!SettingsService.info(
                            settings.profile(automation.modelProfileId()).settings())
                    .configured()) throw new HarnessException("INVALID", "模型未配置完成，请在设置中补全模型名称和 API Key");
            sessionId = sessions.createSessionInWorkspace(title, automation.workspaceId())
                    .id();
        } catch (HarnessException error) {
            return store.record(automation.id(), trigger, scheduledFor, now, "FAILED", error.getMessage(), null);
        }
        var execution = store.record(automation.id(), trigger, scheduledFor, now, "STARTED", "", sessionId);
        SessionStore.Submission submission;
        try {
            Supplier<SessionStore.Submission> submit = () ->
                    engine.submit(
                            sessionId,
                            UUID.randomUUID().toString(),
                            automation.prompt(),
                            "NORMAL",
                            selection,
                            ApprovalMode.valueOf(automation.approvalMode()));
            submission = automation.allowCommands() ? submit.get() : unattended.withoutCommands(sessionId, submit);
        } catch (HarnessException error) {
            return store.fail(execution.id(), error.getMessage());
        } catch (RuntimeException error) {
            log.warn("Automation {} submission failed", automation.id(), error);
            return store.fail(execution.id(), "提交运行失败，请查看服务日志");
        }
        try {
            return store.attachRun(execution.id(), submission.run().id());
        } catch (RuntimeException error) {
            // The run is already executing; keep the trigger as started instead of reporting a false failure.
            log.warn(
                    "Automation {} could not link run {}",
                    automation.id(),
                    submission.run().id(),
                    error);
            return execution;
        }
    }

    /** Translate a schedule into a five-field cron; MANUAL yields an empty expression. */
    String cron(String kind, String time, int weekday, String custom) {
        if (!KINDS.contains(kind)) throw new HarnessException("INVALID", "不支持的调度类型");
        if (kind.equals("MANUAL")) return "";
        if (kind.equals("CUSTOM")) return custom(custom);
        if (!TIME.matcher(time).matches()) throw new HarnessException("INVALID", "时间格式应为 HH:mm");
        String at = Integer.parseInt(time.substring(3)) + " " + Integer.parseInt(time.substring(0, 2));
        return switch (kind) {
            case "HOURLY" -> Integer.parseInt(time.substring(3)) + " * * * *";
            case "DAILY" -> at + " * * *";
            case "WEEKDAYS" -> at + " * * 1-5";
            default -> {
                if (weekday < 0 || weekday > 6) throw new HarnessException("INVALID", "星期需为 0（周日）到 6（周六）");
                yield at + " * * " + weekday;
            }
        };
    }

    // Five standard fields; a single minute value keeps runs at most once per hour.
    private String custom(String value) {
        String[] fields = value == null ? new String[0] : value.strip().split("\\s+");
        String cron = String.join(" ", fields);
        if (fields.length != 5
                || cron.length() > 100
                || !MINUTE.matcher(fields[0]).matches()
                || !CronExpression.isValidExpression("0 " + cron))
            throw new HarnessException("INVALID", "请填写 5 段 cron（分 时 日 月 周），分钟需为单个数字，即最多每小时一次");
        // Spring requires both day fields to match, unlike classic cron's either-or; reject the ambiguous form.
        if (!Set.of("*", "?").contains(fields[2]) && !Set.of("*", "?").contains(fields[4]))
            throw new HarnessException("INVALID", "cron 的“日”和“周”不能同时指定，请保留其中一个为 *");
        if (next(cron, clock.instant()) == null) throw new HarnessException("INVALID", "该 cron 表达式没有可运行的时间");
        return cron;
    }

    private Instant next(String cron, Instant after) {
        if (cron.isEmpty()) return null;
        var next = CronExpression.parse("0 " + cron).next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }
}
