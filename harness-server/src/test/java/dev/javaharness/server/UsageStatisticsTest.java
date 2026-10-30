package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.adapters.usage.LocalAgentUsageReader;
import dev.javaharness.server.service.UsageStatisticsService;
import dev.javaharness.spi.HarnessException;
import io.airlift.compress.zstd.ZstdOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class UsageStatisticsTest {
    @TempDir
    Path home;

    private JdbcTemplate jdbc;
    private JdbcSessionStore store;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        store = new JdbcSessionStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    private void write(Path file, String... lines) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
    }

    private static String claude(String id, Instant at, String model, int output) {
        return """
                {"type":"assistant","requestId":"req","timestamp":"%s","message":{"id":"%s","model":"%s","usage":\
                {"input_tokens":2,"cache_creation_input_tokens":10,"cache_read_input_tokens":100,"output_tokens":%d}}}""".formatted(at, id, model, output);
    }

    private static String codexTokens(Instant at, long total, int input, int cached, int output) {
        return """
                {"timestamp":"%s","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":\
                {"total_tokens":%d},"last_token_usage":{"input_tokens":%d,"cached_input_tokens":%d,"output_tokens":%d}}}}""".formatted(at, total, input, cached, output);
    }

    @Test
    void claudeMessagesAreDedupedAcrossBlocksAndResumedFiles() throws Exception {
        Path projects = home.resolve(".claude/projects");
        write(
                projects.resolve("p/a.jsonl"),
                claude("m1", now, "claude-x", 1),
                claude("m1", now, "claude-x", 50),
                "{\"type\":\"user\",\"usage\":{}}",
                claude("m2", now, "<synthetic>", 9),
                "{not json \"usage\"");
        // A resumed session copies earlier messages into a new file.
        write(
                projects.resolve("p/b/subagents/c.jsonl"),
                claude("m1", now, "claude-x", 50),
                claude("m3", now, "claude-y", 5));

        var usage = new LocalAgentUsageReader(home).usageSince(Instant.EPOCH);

        assertThat(usage)
                .extracting(
                        LocalAgentUsageReader.AgentUsage::model,
                        LocalAgentUsageReader.AgentUsage::inputTokens,
                        LocalAgentUsageReader.AgentUsage::outputTokens,
                        LocalAgentUsageReader.AgentUsage::cachedTokens)
                .containsExactlyInAnyOrder(tuple("claude-x", 112L, 50L, 100L), tuple("claude-y", 112L, 5L, 100L));
    }

    private void writeZstd(Path file, String... lines) throws Exception {
        Files.createDirectories(file.getParent());
        try (var out = new ZstdOutputStream(Files.newOutputStream(file))) {
            out.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String workbuddy(String messageId, long at, int input, int cached, int output) {
        return """
                {"type":"function_call","timestamp":%d,"providerData":{"messageId":"%s","model":"hy3","usage":                {"inputTokens":%d,"outputTokens":%d,"inputTokensDetails":[{"cached_tokens":%d}]}}}""".formatted(at, messageId, input, output, cached);
    }

    @Test
    void workbuddyDedupesParallelToolCallsButKeepsReusedMessageIds() throws Exception {
        long at = now.toEpochMilli();
        write(
                home.resolve(".workbuddy/projects/p/a.jsonl"),
                // A partially streamed row is superseded by the final one with more output.
                workbuddy("w1", at, 300, 200, 4),
                // One reply with two tool calls repeats the same usage.
                workbuddy("w1", at, 300, 200, 10),
                workbuddy("w1", at, 300, 200, 10),
                // A later request may reuse the message id with different usage.
                workbuddy("w1", at, 500, 0, 20),
                "{\"type\":\"function_call_result\",\"output\":\"usage\"}");

        var reader = new LocalAgentUsageReader(home);
        assertThat(reader.available(LocalAgentUsageReader.WORKBUDDY)).isTrue();
        assertThat(reader.usageSince(Instant.EPOCH))
                .extracting(
                        LocalAgentUsageReader.AgentUsage::source,
                        LocalAgentUsageReader.AgentUsage::model,
                        LocalAgentUsageReader.AgentUsage::inputTokens,
                        LocalAgentUsageReader.AgentUsage::cachedTokens)
                .containsExactlyInAnyOrder(tuple("workbuddy", "hy3", 300L, 200L), tuple("workbuddy", "hy3", 500L, 0L));
    }

    @Test
    void qoderUsesTheClaudeFormatAndSkipsCreditOnlyRecords() throws Exception {
        String creditOnly = """
                {"type":"assistant","timestamp":"%s","message":{"id":"q2","model":"qfmodel","usage":                {"input_tokens":0,"output_tokens":0,"credits":0.2}}}""".formatted(now);
        // The same message id in Claude Code must not be merged with Qoder's.
        write(home.resolve(".qoder/projects/p/a.jsonl"), claude("m1", now, "gpt-x", 7), creditOnly);
        write(home.resolve(".claude/projects/p/a.jsonl"), claude("m1", now, "claude-x", 9));

        assertThat(new LocalAgentUsageReader(home).usageSince(Instant.EPOCH))
                .extracting(LocalAgentUsageReader.AgentUsage::source, LocalAgentUsageReader.AgentUsage::model)
                .containsExactlyInAnyOrder(tuple("qoder", "gpt-x"), tuple("claude-code", "claude-x"));
    }

    @Test
    void compressedCodexRolloutsAreReadAndBrokenOnesSkipped() throws Exception {
        Path sessions = home.resolve(".codex/sessions/2025/12/01");
        String context =
                "{\"timestamp\":\"" + now + "\",\"type\":\"turn_context\",\"payload\":{\"model\":\"gpt-old\"}}";
        writeZstd(sessions.resolve("rollout-a.jsonl.zst"), context, codexTokens(now, 50, 40, 10, 10));
        // A rollout mid-compression exists twice; only the plain copy counts.
        write(sessions.resolve("rollout-b.jsonl"), context, codexTokens(now, 30, 20, 0, 10));
        writeZstd(sessions.resolve("rollout-b.jsonl.zst"), context, codexTokens(now, 30, 20, 0, 10));
        Files.write(
                sessions.resolve("rollout-c.jsonl.zst"), new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 1, 2, 3});
        Files.write(sessions.resolve("rollout-d.jsonl.zst"), new byte[0]);
        byte[] whole = Files.readAllBytes(sessions.resolve("rollout-a.jsonl.zst"));
        Files.write(sessions.resolve("rollout-e.jsonl.zst"), Arrays.copyOf(whole, whole.length / 2));

        assertThat(new LocalAgentUsageReader(home).usageSince(Instant.EPOCH))
                .extracting(LocalAgentUsageReader.AgentUsage::model, LocalAgentUsageReader.AgentUsage::inputTokens)
                .containsExactlyInAnyOrder(tuple("gpt-old", 40L), tuple("gpt-old", 20L));
    }

    @Test
    void codexCountsEachChangedTotalOnceWithTheTurnModel() throws Exception {
        write(
                home.resolve(".codex/sessions/2026/10/30/rollout.jsonl"),
                "{\"timestamp\":\"" + now + "\",\"type\":\"turn_context\",\"payload\":{\"model\":\"gpt-a\"}}",
                "{\"timestamp\":\"" + now
                        + "\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null}}",
                codexTokens(now, 120, 100, 0, 20),
                codexTokens(now, 120, 100, 0, 20),
                "{\"timestamp\":\"" + now + "\",\"type\":\"turn_context\",\"payload\":{\"model\":\"gpt-b\"}}",
                codexTokens(now, 300, 170, 90, 10));
        var reader = new LocalAgentUsageReader(home);

        assertThat(reader.available(LocalAgentUsageReader.CODEX)).isTrue();
        assertThat(reader.available(LocalAgentUsageReader.CLAUDE_CODE)).isFalse();
        assertThat(reader.usageSince(Instant.EPOCH))
                .extracting(
                        LocalAgentUsageReader.AgentUsage::model,
                        LocalAgentUsageReader.AgentUsage::inputTokens,
                        LocalAgentUsageReader.AgentUsage::cachedTokens)
                .containsExactly(tuple("gpt-a", 100L, 0L), tuple("gpt-b", 170L, 90L));
        assertThat(reader.usageSince(now.plusSeconds(1))).isEmpty();
    }

    @Test
    void statisticsMergeSourcesByModelAndZeroFilledDays() throws Exception {
        jdbc.update(
                "INSERT INTO harness_usage_ledger VALUES (?,?,?,?,?,?,?,?)",
                "r1",
                1,
                "openai-compatible",
                "local-model",
                300,
                40,
                200,
                now.atOffset(ZoneOffset.UTC));
        jdbc.update(
                "INSERT INTO harness_usage_ledger VALUES (?,?,?,?,?,?,?,?)",
                "r0",
                1,
                "openai-compatible",
                "local-model",
                999,
                1,
                0,
                now.minus(30, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC));
        write(home.resolve(".claude/projects/p/a.jsonl"), claude("m1", now, "claude-x", 8));
        var service = new UsageStatisticsService(store, new LocalAgentUsageReader(home), ZoneOffset.UTC);

        var week = service.statistics(7);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(week.from()).isEqualTo(today.minusDays(6));
        assertThat(week.totals()).isEqualTo(new UsageStatisticsService.Totals(412, 48, 300, 2));
        assertThat(week.sources())
                .extracting(
                        UsageStatisticsService.Source::id,
                        UsageStatisticsService.Source::available,
                        source -> source.totals().calls())
                .containsExactly(
                        tuple("harness", true, 1L),
                        tuple("claude-code", true, 1L),
                        tuple("codex", false, 0L),
                        tuple("workbuddy", false, 0L),
                        tuple("qoder", false, 0L));
        assertThat(week.models())
                .extracting(UsageStatisticsService.Model::model)
                .containsExactly("local-model", "claude-x");
        assertThat(week.days()).hasSize(7);
        assertThat(week.days().getLast().date()).isEqualTo(today);
        assertThat(week.days().getLast().tokens()).isEqualTo(Map.of("harness", 340L, "claude-code", 120L));
        // Only the single-day range carries an hourly series.
        assertThat(week.hours()).isEmpty();
        var day = service.statistics(1);
        assertThat(day.hours()).hasSize(24);
        assertThat(day.hours().get(now.atZone(ZoneOffset.UTC).getHour()).tokens())
                .isEqualTo(Map.of("harness", 340L, "claude-code", 120L));
        assertThat(day.hours().stream().filter(hour -> !hour.tokens().isEmpty()))
                .hasSize(1);
        assertThat(week.days().getFirst().tokens()).isEmpty();
        // The heatmap spans a year of Sunday-aligned weeks regardless of the selected range.
        var heatmap = week.heatmap();
        assertThat(heatmap.from().getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(heatmap.zone()).isEqualTo("UTC+00:00");
        assertThat(heatmap.tokens()).hasSize((int) ChronoUnit.DAYS.between(heatmap.from(), today) + 1);
        assertThat(heatmap.tokens().getLast()).isEqualTo(460L);
        assertThat(heatmap.tokens().stream().mapToLong(Long::longValue).sum()).isEqualTo(1460L);

        var all = service.statistics(0);
        assertThat(all.from()).isNull();
        assertThat(all.totals().calls()).isEqualTo(3);
        assertThat(all.days()).hasSize(31);
        assertThatThrownBy(() -> service.statistics(-1)).isInstanceOf(HarnessException.class);
    }
}
