package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.attachment.AttachmentStore;
import dev.javaharness.adapters.memory.JdbcMemoryStore;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.core.TokenBudget;
import dev.javaharness.server.service.ComposerInputService;
import dev.javaharness.server.service.MemoryService;
import dev.javaharness.server.service.PromptOptimizationService;
import dev.javaharness.server.service.PromptOptimizationService.Draft;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class PromptOptimizationServiceTest {
    @TempDir
    Path root;

    JdbcSessionStore sessions;
    JdbcTemplate jdbc;
    JdbcMemoryStore memoryStore;
    MemoryService memories;
    PromptOptimizationService optimizer;
    ComposerInputService inputs;
    Session session;
    String otherWorkspace;
    final ObjectMapper json = new ObjectMapper();
    final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger released = new AtomicInteger();
    final CountDownLatch gate = new CountDownLatch(1);
    Duration timeout = Duration.ofSeconds(5);
    int window = 8000;
    String answer = "请分析 PostgreSQL 连接池问题，说明证据、修改范围与验证方式。";
    boolean block;
    boolean fail;
    boolean switchWorkspace;
    int historySequence;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        memoryStore = new JdbcMemoryStore(jdbc, tx);
        String workspace = sessions.saveWorkspace("", "项目", List.of(root.toString()), "folder", "#112233", 0)
                .id();
        otherWorkspace = sessions.saveWorkspace(
                        "", "其他项目", List.of(root.resolve("other").toString()), "folder", "#112233", 0)
                .id();
        session = sessions.createSessionInWorkspace("优化测试", workspace);
        var model = new ModelProvider() {
            public ModelInfo info() {
                return new ModelInfo("capturing", "optimizer", true);
            }

            public void stream(ModelRequest request, Cancellation cancellation, Consumer<String> onText)
                    throws Exception {
                requests.add(request);
                if (block) gate.await();
                cancellation.check();
                if (fail) throw new IllegalStateException("private provider detail");
                if (switchWorkspace) sessions.selectWorkspace(session.id(), otherWorkspace);
                onText.accept(answer);
            }
        };
        RunConfigurationSource configurations = new RunConfigurationSource() {
            public RunConfiguration snapshot() {
                return snapshot(ModelSelection.DEFAULT);
            }

            public RunConfiguration snapshot(ModelSelection selection) {
                return new RunConfiguration(
                        model.info(),
                        timeout,
                        window,
                        512,
                        32,
                        TokenCounter.approximate(),
                        workspace -> {
                            assertThat(workspace).isEmpty();
                            return new RunConfiguration.Resources(
                                    model, WorkspaceTools.disabled(), released::incrementAndGet);
                        },
                        new ModelSelection(
                                selection.profileId() == null ? "default" : selection.profileId(),
                                selection.reasoningEffort() == null ? "low" : selection.reasoningEffort()));
            }
        };
        inputs = new ComposerInputService(sessions, new AttachmentStore(jdbc));
        memories = new MemoryService(memoryStore, sessions, configurations, json);
        memories.configure(true, "default", memoryStore.settings().revision());
        optimizer = new PromptOptimizationService(sessions, configurations, inputs, memories, json);
    }

    @AfterEach
    void close() {
        gate.countDown();
        if (optimizer != null) optimizer.close();
        if (memories != null) memories.close();
    }

    /** 构造有效的完成轮次，保持模型输入与最终回答的真实存储格式。 */
    void history(String prompt, String response) {
        var run = sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        prompt,
                        "NORMAL",
                        new ModelInfo("test", "test", true))
                .run();
        // Windows 时钟精度可能让多轮同一时刻创建，显式安排测试历史的先后顺序。
        jdbc.update(
                "UPDATE harness_run SET created_at=? WHERE id=?",
                Timestamp.from(Instant.EPOCH.plusSeconds(++historySequence)),
                run.id());
        sessions.transition(run.id(), RunStatus.RUNNING, "");
        for (int i = 0; i < response.length(); i += 1000)
            sessions.appendText(run.id(), response.substring(i, Math.min(i + 1000, response.length())));
        sessions.completeRun(
                run.id(), List.of(new ModelMessage("user", prompt), new ModelMessage("assistant", response)));
    }

    /** 保存独立于历史来源的生效或归档记忆。 */
    String memory(String scope, String content, String status) {
        return memories.save("", scope, "PostgreSQL 约定", content, "FACT", List.of(), status, 0, null, "", 0)
                .id();
    }

    Draft draft(String text) {
        return new Draft(session.id(), text, "chosen-profile", "high", List.of(), List.of());
    }

    @Test
    void includesScopedMemoryHistoryAndAttachmentsWithoutCreatingRunsOrBindingUploads() throws Exception {
        history("PostgreSQL 连接池异常", "已检查配置，连接池上限为12。");
        var local = memory(session.workspaceId(), "PostgreSQL 项目使用连接池", "ACTIVE");
        var global = memory("", "PostgreSQL 输出使用中文", "ACTIVE");
        memory(otherWorkspace, "PostgreSQL 其他项目机密", "ACTIVE");
        memory(session.workspaceId(), "PostgreSQL 已废弃方案", "ARCHIVED");
        Files.writeString(root.resolve("config.txt"), "maximumPoolSize=12");
        var attachment = inputs.upload("error.txt", "connection timeout".getBytes(StandardCharsets.UTF_8));
        var before = sessions.snapshot(session.id());
        var result = optimizer.optimize(new Draft(
                session.id(),
                "修复这个问题",
                "chosen-profile",
                "high",
                List.of(new ComposerInputService.Mention(1, "config.txt", false)),
                List.of(attachment.id())));
        assertThat(result.prompt()).isEqualTo(answer);
        assertThat(result.historyTurns()).isEqualTo(1);
        assertThat(result.memories())
                .extracting(PromptOptimizationService.MemoryReference::id)
                .containsExactlyInAnyOrder(local, global);
        var request = requests.getFirst();
        var payload = json.readTree(request.messages().getFirst().content());
        assertThat(payload.get("draft").asText()).isEqualTo("修复这个问题");
        assertThat(payload.get("attachedContext").asText()).contains("maximumPoolSize=12", "connection timeout");
        assertThat(payload.get("recentConversation").get(0).get("assistant").asText())
                .contains("连接池上限为12");
        assertThat(payload.get("retrievedMemory").asText()).doesNotContain("其他项目机密", "已废弃方案");
        assertThat(request.tools()).isEmpty();
        assertThat(request.finalAnswerOnly()).isTrue();
        assertThat(request.reasoningEffort()).isEqualTo("high");
        assertThat(sessions.snapshot(session.id())).isEqualTo(before);
        assertThat(inputs.content(attachment.id()).attachment().runId()).isNull();
        assertThat(released.get()).isEqualTo(1);
    }

    @Test
    void welcomeDraftUsesOnlyGlobalMemoryAndHonorsDisabledMemory() throws Exception {
        memory(session.workspaceId(), "PostgreSQL 本地项目", "ACTIVE");
        var global = memory("", "PostgreSQL 全局约定", "ACTIVE");
        var result = optimizer.optimize(new Draft("", "PostgreSQL", null, null, null, null));
        assertThat(result.historyTurns()).isZero();
        assertThat(result.memories())
                .extracting(PromptOptimizationService.MemoryReference::id)
                .containsExactly(global);
        memories.configure(false, "default", memoryStore.settings().revision());
        assertThat(optimizer.optimize(draft("PostgreSQL")).memories()).isEmpty();
        assertThat(json.readTree(requests.getLast().messages().getFirst().content())
                        .get("retrievedMemory")
                        .asText())
                .isEmpty();
    }

    @Test
    void keepsRecentCompleteTurnsAndFitsTheFullSerializedInputBudget() throws Exception {
        for (int i = 0; i < 9; i++) history("PostgreSQL 问题" + i, "回答" + i);
        var result = optimizer.optimize(draft("继续 PostgreSQL"));
        assertThat(result.historyTurns()).isEqualTo(6);
        var request = requests.getLast();
        var turns = json.readTree(request.messages().getFirst().content()).get("recentConversation");
        assertThat(turns.get(0).get("user").asText()).endsWith("3");
        assertThat(turns.get(5).get("user").asText()).endsWith("8");
        history("过大的最近一轮", "长".repeat(12000));
        window = 2048;
        assertThat(optimizer.optimize(draft("短草稿")).historyTurns()).isZero();
        request = requests.getLast();
        int tokens = new TokenBudget(TokenCounter.approximate())
                .input(request.messages(), request.instructions(), request.tools());
        assertThat(tokens + 512 + TokenBudget.safety(window)).isLessThanOrEqualTo(window);
        assertThat(request.context().tokens()).isEqualTo(tokens);
    }

    @Test
    void rejectsEmptyOversizedAndForeignAttachmentInputsBeforeCallingModel() {
        assertThatThrownBy(() -> optimizer.optimize(draft("  "))).isInstanceOf(HarnessException.class);
        window = 2048;
        assertThatThrownBy(() -> optimizer.optimize(draft("大".repeat(12000)))).hasMessageContaining("上下文预算");
        var other = sessions.createSession("其他会话");
        var run = sessions.submit(
                        other.id(), UUID.randomUUID().toString(), "附件", "NORMAL", new ModelInfo("test", "test", true))
                .run();
        var upload = inputs.upload("private.txt", "secret".getBytes(StandardCharsets.UTF_8));
        inputs.bind(run.id(), List.of(upload.id()));
        assertThatThrownBy(() ->
                        optimizer.optimize(new Draft(session.id(), "优化", null, null, List.of(), List.of(upload.id()))))
                .hasMessageContaining("附件不存在");
        assertThat(requests).isEmpty();
    }

    @Test
    void modelFailuresAndEmptyResultsReleaseResourcesAndDoNotExposeProviderDetails() {
        fail = true;
        assertThatThrownBy(() -> optimizer.optimize(draft("优化")))
                .hasMessageContaining("优化失败")
                .hasMessageNotContaining("private");
        fail = false;
        answer = " ";
        assertThatThrownBy(() -> optimizer.optimize(draft("优化"))).hasMessageContaining("未返回有效");
        assertThat(released.get()).isEqualTo(2);
        assertThat(sessions.snapshot(session.id()).runs()).isEmpty();
    }

    @Test
    void timeoutInterruptsTheProviderAndEventuallyClosesResources() throws Exception {
        block = true;
        timeout = Duration.ofMillis(200);
        assertThatThrownBy(() -> optimizer.optimize(draft("优化"))).hasMessageContaining("优化超时");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> released.get() == 1);
        assertThat(sessions.snapshot(session.id()).runs()).isEmpty();
    }

    @Test
    void preservesSelectedPluginCommandWithoutLettingTheModelRewriteOrDuplicateIt() throws Exception {
        var result = optimizer.optimize(draft("/code-review:run 检查连接池问题"));
        assertThat(result.prompt()).isEqualTo("/code-review:run\n" + answer);
        assertThat(json.readTree(requests.getLast().messages().getFirst().content())
                        .get("draft")
                        .asText())
                .isEqualTo("检查连接池问题");
        answer = "/other:command\n优化后的正文";
        assertThat(optimizer.optimize(draft("/code-review:run 检查问题")).prompt()).isEqualTo("/code-review:run\n优化后的正文");
        answer = "/other:command /another:run\n优化后的正文";
        assertThat(optimizer.optimize(draft("普通草稿")).prompt()).isEqualTo("优化后的正文");
        int called = requests.size();
        assertThatThrownBy(() -> optimizer.optimize(draft("/code-review:run "))).hasMessageContaining("补充");
        assertThat(requests).hasSize(called);
    }

    @Test
    void discardsResultsWhenWorkspaceChangesDuringModelCall() {
        switchWorkspace = true;
        assertThatThrownBy(() -> optimizer.optimize(draft("PostgreSQL"))).hasMessageContaining("工作区已变化");
        assertThat(released.get()).isEqualTo(1);
    }
}
