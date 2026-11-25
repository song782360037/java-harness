package dev.javaharness.server.service;

import dev.javaharness.adapters.bot.BotStore;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bot 专属例行任务调度器：复用 Spring 5 段 Cron 表达式（支持时区与 7 天错过补跑），
 * 到点作为该 Bot 的独立后台任务运行并把结论投回其聊天。
 */
public final class BotRoutineScheduler implements AutoCloseable {
    public static final Set<String> KINDS = Set.of("HOURLY", "DAILY", "WEEKDAYS", "WEEKLY", "CUSTOM", "MANUAL");
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern MINUTE = Pattern.compile("[0-5]?\\d");
    private static final Duration CATCH_UP_WINDOW = Duration.ofDays(7);
    private static final Logger log = LoggerFactory.getLogger(BotRoutineScheduler.class);

    public record RoutineDraft(
            String id,
            String name,
            String instruction,
            String playbookId,
            String modelProfileId,
            String reasoningEffort,
            String scheduleKind,
            String scheduleTime,
            Integer scheduleWeekday,
            String cron,
            String timezone,
            Boolean enabled,
            long revision) {}

    private final BotStore store;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final BotOrchestrator orchestrator;
    private final Clock clock;
    private final ZoneId defaultZone;
    private DesktopUpdateGate updateGate;
    private ScheduledExecutorService scheduler;
    private volatile boolean closed;

    public BotRoutineScheduler(
            BotStore store,
            SessionStore sessions,
            TransactionTemplate tx,
            BotOrchestrator orchestrator,
            Clock clock,
            ZoneId defaultZone) {
        this.store = store;
        this.sessions = sessions;
        this.tx = tx;
        this.orchestrator = orchestrator;
        this.clock = clock;
        this.defaultZone = defaultZone;
    }

    public void useUpdateGate(DesktopUpdateGate gate) {
        this.updateGate = gate;
    }

    public synchronized void start(Duration interval) {
        if (scheduler != null || closed) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("harness-bot-routines").daemon().factory());
        scheduler.scheduleWithFixedDelay(this::safeTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public List<Routine> list(String botId) {
        store.getBot(botId);
        return store.routines(botId);
    }

    public synchronized Routine save(String botId, RoutineDraft draft) {
        store.getBot(botId);
        String name = draft.name() == null ? "" : draft.name().strip();
        String instruction =
                draft.instruction() == null ? "" : draft.instruction().strip();
        if (name.isEmpty() || name.length() > 100) throw new HarnessException("INVALID", "例行任务名称需为 1–100 个字符");
        if (instruction.isEmpty()) throw new HarnessException("INVALID", "例行任务指令不能为空");

        Routine existing = draft.id() == null || draft.id().isBlank() ? null : store.routine(draft.id());
        if (existing != null && !existing.botId().equals(botId)) {
            throw new HarnessException("CONFLICT", "例行任务不属于该 Bot");
        }

        String kind = draft.scheduleKind() == null || draft.scheduleKind().isBlank()
                ? (existing != null ? existing.scheduleKind() : "DAILY")
                : draft.scheduleKind().strip().toUpperCase();
        String time = draft.scheduleTime() == null || draft.scheduleTime().isBlank()
                ? (existing != null && !existing.scheduleTime().isBlank() ? existing.scheduleTime() : "09:00")
                : draft.scheduleTime().strip();
        int weekday = draft.scheduleWeekday() == null
                ? (existing != null ? existing.scheduleWeekday() : 1)
                : draft.scheduleWeekday();
        ZoneId zone = parseZone(draft.timezone() == null && existing != null ? existing.timezone() : draft.timezone());
        String cronExpr = buildCron(
                kind, time, weekday, draft.cron() == null && existing != null ? existing.cron() : draft.cron(), zone);
        if (!TIME.matcher(time).matches()) time = "09:00";
        boolean enabled = draft.enabled() != null ? draft.enabled() : (existing == null || existing.enabled());
        Instant now = clock.instant();
        Instant nextRunAt = enabled ? nextTrigger(cronExpr, now, zone) : null;
        String playbookId = draft.playbookId() == null
                ? (existing != null ? existing.playbookId() : "")
                : draft.playbookId().strip();
        String modelProfileId = draft.modelProfileId() == null
                ? (existing != null ? existing.modelProfileId() : "")
                : draft.modelProfileId().strip();
        String reasoningEffort = draft.reasoningEffort() == null && existing != null
                ? existing.reasoningEffort()
                : draft.reasoningEffort();
        long rev = draft.revision() > 0 ? draft.revision() : (existing != null ? existing.revision() : 0);

        Routine toSave = new Routine(
                existing == null ? "" : existing.id(),
                botId,
                name,
                instruction,
                playbookId,
                modelProfileId,
                reasoningEffort,
                kind,
                time,
                weekday,
                cronExpr,
                zone.getId(),
                enabled,
                nextRunAt,
                existing == null ? null : existing.lastRunAt(),
                existing == null ? "" : existing.lastTaskId(),
                existing == null ? "" : existing.lastStatus(),
                existing == null ? now : existing.createdAt(),
                rev);
        Routine saved = write(() -> store.saveRoutine(toSave));
        if (existing == null) {
            var bot = store.getBot(botId);
            orchestrator.pushNotice(
                    "ROUTINE_SAVED", bot.id(), bot.name(), "新增例行任务", bot.name() + " 已安排「" + saved.name() + "」");
        }
        return saved;
    }

    public synchronized Routine toggle(String routineId, boolean enabled) {
        Routine existing = store.routine(routineId);
        ZoneId zone = parseZone(existing.timezone());
        Instant next = enabled ? nextTrigger(existing.cron(), clock.instant(), zone) : null;
        return write(() -> store.saveRoutine(new Routine(
                existing.id(),
                existing.botId(),
                existing.name(),
                existing.instruction(),
                existing.playbookId(),
                existing.modelProfileId(),
                existing.reasoningEffort(),
                existing.scheduleKind(),
                existing.scheduleTime(),
                existing.scheduleWeekday(),
                existing.cron(),
                existing.timezone(),
                enabled,
                next,
                existing.lastRunAt(),
                existing.lastTaskId(),
                existing.lastStatus(),
                existing.createdAt(),
                existing.revision())));
    }

    public synchronized void delete(String routineId) {
        write(() -> {
            store.deleteRoutine(routineId);
            return null;
        });
    }

    public synchronized Task runNow(String routineId) {
        Routine routine = store.routine(routineId);
        if (store.hasActiveTaskForRoutine(routineId)) {
            throw new HarnessException("CONFLICT", "该例行任务的上一次执行仍在进行中");
        }
        return trigger(routine);
    }

    /** 检查到期例行任务；暴露给集成测试确定性触发。 */
    public synchronized void tick() {
        if (closed) return;
        if (updateGate != null && !updateGate.enter()) return;
        try {
            Instant now = clock.instant();
            for (Routine routine : store.dueRoutines(now)) {
                if (closed) return;
                try {
                    checkDue(routine, now);
                } catch (RuntimeException error) {
                    log.warn("Bot routine {} tick failed", routine.id(), error);
                }
            }
        } finally {
            if (updateGate != null) updateGate.leave();
        }
    }

    private void checkDue(Routine routine, Instant now) {
        ZoneId zone = parseZone(routine.timezone());
        Instant window = now.minus(CATCH_UP_WINDOW);
        boolean expired = routine.nextRunAt() != null && routine.nextRunAt().isBefore(window);
        Instant latest = null;
        for (Instant probe = expired ? nextTrigger(routine.cron(), window, zone) : routine.nextRunAt();
                probe != null && !probe.isAfter(now);
                probe = nextTrigger(routine.cron(), probe, zone)) {
            latest = probe;
        }
        Instant next = nextTrigger(routine.cron(), now, zone);
        write(() -> {
            store.updateRoutineSchedule(routine.id(), next);
            return null;
        });
        if (latest == null) {
            write(() -> {
                store.recordRoutineTrigger(routine.id(), "", "SKIPPED", now);
                return null;
            });
            return;
        }
        if (store.hasActiveTaskForRoutine(routine.id())) {
            write(() -> {
                store.recordRoutineTrigger(routine.id(), routine.lastTaskId(), "SKIPPED", now);
                return null;
            });
            return;
        }
        trigger(routine);
    }

    private Task trigger(Routine routine) {
        Instant now = clock.instant();
        try {
            Task task = orchestrator.startRoutineTask(
                    routine.botId(),
                    routine.id(),
                    "例行 · " + routine.name(),
                    routine.instruction(),
                    routine.playbookId(),
                    routine.modelProfileId());
            write(() -> {
                store.recordRoutineTrigger(routine.id(), task.id(), "RUNNING", now);
                return null;
            });
            return task;
        } catch (RuntimeException error) {
            write(() -> {
                store.recordRoutineTrigger(routine.id(), "", "FAILED", now);
                return null;
            });
            throw error;
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException error) {
            log.warn("Bot routine tick failed", error);
        }
    }

    private ZoneId parseZone(String raw) {
        if (raw == null || raw.isBlank()) return defaultZone;
        try {
            return ZoneId.of(raw.strip());
        } catch (Exception error) {
            throw new HarnessException("INVALID", "不支持的时区：" + raw);
        }
    }

    private String buildCron(String kind, String time, int weekday, String custom, ZoneId zone) {
        if (!KINDS.contains(kind)) throw new HarnessException("INVALID", "不支持的调度类型：" + kind);
        if ("MANUAL".equals(kind)) return "";
        if ("CUSTOM".equals(kind)) {
            String[] fields = custom == null ? new String[0] : custom.strip().split("\\s+");
            String normalized = String.join(" ", fields);
            if (fields.length != 5
                    || normalized.length() > 80
                    || !MINUTE.matcher(fields[0]).matches()
                    || !CronExpression.isValidExpression("0 " + normalized)) {
                throw new HarnessException("INVALID", "请填写 5 段 cron（分 时 日 月 周），分钟需为单个数字");
            }
            if (!Set.of("*", "?").contains(fields[2]) && !Set.of("*", "?").contains(fields[4])) {
                throw new HarnessException("INVALID", "cron 的日和周不能同时指定，请保留其中一个为 *");
            }
            if (nextTrigger(normalized, clock.instant(), zone) == null) {
                throw new HarnessException("INVALID", "该 cron 表达式没有可运行的时间");
            }
            return normalized;
        }
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

    private Instant nextTrigger(String cron, Instant after, ZoneId zone) {
        if (cron == null || cron.isBlank()) return null;
        var next = CronExpression.parse("0 " + cron).next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    private <T> T write(Supplier<T> work) {
        synchronized (sessions) {
            return tx.execute(status -> work.get());
        }
    }

    @Override
    public void close() {
        closed = true;
        ScheduledExecutorService current;
        synchronized (this) {
            current = scheduler;
        }
        if (current != null) {
            current.shutdown();
            try {
                current.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
