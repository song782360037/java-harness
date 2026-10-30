package dev.javaharness.server.service;

import dev.javaharness.adapters.usage.LocalAgentUsageReader;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 使用统计：合并本项目的用量台账与本机 Claude Code、Codex、WorkBuddy、Qoder 日志，按来源、模型和自然日（本机时区）汇总 token。
 */
public final class UsageStatisticsService {
    public static final String HARNESS = "harness";
    private static final Map<String, String> LABELS = Map.of(
            HARNESS,
            "Java Harness",
            LocalAgentUsageReader.CLAUDE_CODE,
            "Claude Code",
            LocalAgentUsageReader.CODEX,
            "Codex",
            LocalAgentUsageReader.WORKBUDDY,
            "WorkBuddy",
            LocalAgentUsageReader.QODER,
            "Qoder");
    private static final List<String> SOURCES = List.of(
            HARNESS,
            LocalAgentUsageReader.CLAUDE_CODE,
            LocalAgentUsageReader.CODEX,
            LocalAgentUsageReader.WORKBUDDY,
            LocalAgentUsageReader.QODER);
    private final SessionStore store;
    private final LocalAgentUsageReader agents;
    private final ZoneId zone;

    public record Totals(long inputTokens, long outputTokens, long cachedTokens, long calls) {
        static final Totals EMPTY = new Totals(0, 0, 0, 0);

        Totals plus(long input, long output, long cached) {
            return new Totals(inputTokens + input, outputTokens + output, cachedTokens + cached, calls + 1);
        }
    }

    public record Source(String id, String label, boolean available, Totals totals) {}

    public record Model(String source, String model, Totals totals) {}

    /** 当日各来源的输入与输出 token 之和。 */
    public record Day(LocalDate date, Map<String, Long> tokens) {}

    /** 近一年逐日 token（输入加输出），from 为周日以便按周排成列；zone 为统计所用时区的 UTC 偏移。 */
    public record Heatmap(LocalDate from, String zone, List<Long> tokens) {}

    /** from 为统计起始日，统计全部时为 null；热力图不受所选范围影响。 */
    public record Statistics(
            LocalDate from, Totals totals, List<Source> sources, List<Model> models, List<Day> days, Heatmap heatmap) {}

    private record Entry(String source, LocalDate day, String model, long input, long output, long cached) {}

    public UsageStatisticsService(SessionStore store, LocalAgentUsageReader agents, ZoneId zone) {
        this.store = store;
        this.agents = agents;
        this.zone = zone;
    }

    /** 统计最近 days 个自然日（含今天）；days 为 0 时统计全部。 */
    public Statistics statistics(int days) {
        if (days < 0 || days > 366) throw new HarnessException("INVALID", "统计天数需为 0–366");
        LocalDate today = LocalDate.now(zone);
        LocalDate from = days == 0 ? null : today.minusDays(days - 1L);
        LocalDate heatFrom = today.minusWeeks(52).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate fetchFrom = from == null || heatFrom.isBefore(from) ? heatFrom : from;
        Instant start =
                from == null ? Instant.EPOCH : fetchFrom.atStartOfDay(zone).toInstant();
        List<Entry> entries = new ArrayList<>();
        for (var usage : store.usageSince(start))
            entries.add(new Entry(
                    HARNESS,
                    day(usage.recordedAt()),
                    usage.model(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.cachedTokens()));
        for (var usage : agents.usageSince(start))
            entries.add(new Entry(
                    usage.source(),
                    day(usage.at()),
                    usage.model(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.cachedTokens()));

        Totals totals = Totals.EMPTY;
        Map<String, Totals> bySource = new LinkedHashMap<>();
        Map<List<String>, Totals> byModel = new LinkedHashMap<>();
        TreeMap<LocalDate, Map<String, Long>> byDay = new TreeMap<>();
        long[] heat = new long[(int) ChronoUnit.DAYS.between(heatFrom, today) + 1];
        for (Entry entry : entries) {
            // Clock-skewed future entries would count in totals but never appear on the chart.
            if (entry.day().isAfter(today)) continue;
            if (!entry.day().isBefore(heatFrom))
                heat[(int) ChronoUnit.DAYS.between(heatFrom, entry.day())] += entry.input() + entry.output();
            if (from != null && entry.day().isBefore(from)) continue;
            totals = totals.plus(entry.input(), entry.output(), entry.cached());
            bySource.merge(
                    entry.source(),
                    Totals.EMPTY.plus(entry.input(), entry.output(), entry.cached()),
                    UsageStatisticsService::sum);
            byModel.merge(
                    List.of(entry.source(), entry.model()),
                    Totals.EMPTY.plus(entry.input(), entry.output(), entry.cached()),
                    UsageStatisticsService::sum);
            byDay.computeIfAbsent(entry.day(), key -> new LinkedHashMap<>())
                    .merge(entry.source(), entry.input() + entry.output(), Long::sum);
        }
        List<Source> sources = SOURCES.stream()
                .map(id -> new Source(
                        id,
                        LABELS.get(id),
                        id.equals(HARNESS) || agents.available(id),
                        bySource.getOrDefault(id, Totals.EMPTY)))
                .toList();
        List<Model> models = byModel.entrySet().stream()
                .map(item -> new Model(item.getKey().get(0), item.getKey().get(1), item.getValue()))
                .sorted(Comparator.comparingLong((Model model) ->
                                model.totals().inputTokens() + model.totals().outputTokens())
                        .reversed())
                .toList();
        // Zero-fill every day of the range so the chart keeps a continuous axis.
        List<Day> series = new ArrayList<>();
        LocalDate first = from != null ? from : byDay.isEmpty() ? today : byDay.firstKey();
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1))
            series.add(new Day(date, byDay.getOrDefault(date, Map.of())));
        String offset = zone.getRules().getOffset(Instant.now()).getId();
        Heatmap heatmap = new Heatmap(
                heatFrom,
                "UTC" + (offset.equals("Z") ? "+00:00" : offset),
                Arrays.stream(heat).boxed().toList());
        return new Statistics(from, totals, sources, models, series, heatmap);
    }

    private LocalDate day(Instant at) {
        return at.atZone(zone).toLocalDate();
    }

    private static Totals sum(Totals a, Totals b) {
        return new Totals(
                a.inputTokens() + b.inputTokens(),
                a.outputTokens() + b.outputTokens(),
                a.cachedTokens() + b.cachedTokens(),
                a.calls() + b.calls());
    }
}
