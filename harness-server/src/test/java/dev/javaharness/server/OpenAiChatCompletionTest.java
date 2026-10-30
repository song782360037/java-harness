package dev.javaharness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.javaharness.adapters.*;
import dev.javaharness.core.DefaultAgentEngine;
import dev.javaharness.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class OpenAiChatCompletionTest {
    private HttpServer upstream;
    private ExecutorService handlers;
    private JdbcSessionStore store;
    private JdbcTemplate jdbc;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<Throwable> handlerError = new AtomicReference<>();

    @FunctionalInterface interface Reply { void send(HttpExchange exchange) throws Exception; }

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        store = new JdbcSessionStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(handlers);
    }

    @AfterEach void cleanup() {
        upstream.stop(0);
        handlers.shutdownNow();
        assertThat(handlerError.get()).isNull();
    }

    private void serve(Reply reply) {
        serve("", reply);
    }

    private void serve(String prefix, Reply reply) {
        upstream.createContext(prefix + "/chat/completions", exchange -> {
            calls.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try (exchange) {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestURI().getPath()).isEqualTo(prefix + "/chat/completions");
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                reply.send(exchange);
            } catch (IOException | InterruptedException expectedOnCancellation) {
                // Client cancellation or teardown closes the stub connection.
            } catch (Throwable error) { handlerError.set(error); }
        });
        upstream.start();
    }

    private OpenAiChatCompletionProvider provider() {
        return new OpenAiChatCompletionProvider(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                "test-only-key", "org/example-model:latest", true, Duration.ofSeconds(2));
    }

    private static void headers(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
    }

    private static String chunk(String text, String finish) throws Exception {
        String content = new ObjectMapper().writeValueAsString(text);
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":" + content
                + "},\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\r\n\r\n";
    }

    private static void write(HttpExchange exchange, String text) throws IOException {
        exchange.getResponseBody().write(text.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private Run submit(DefaultAgentEngine engine) {
        return engine.submit(store.createSession("协议验证").id(), UUID.randomUUID().toString(), "解释 Java 虚拟线程 🌱", "NORMAL").run();
    }

    private Run settled(Run run) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !store.run(run.id()).status().active());
        return store.run(run.id());
    }

    @Test void reasoningAliasIsBatchedIntoStepAndKeptOutOfTheAnswer() throws Exception {
        serve(exchange -> {
            headers(exchange);
            var text = new StringBuilder();
            for (String piece : new String[] {"先", "看", "看🌱"}) text.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"").append(piece).append("\"}}]}\n\n");
            write(exchange, text + chunk("结论", null) + chunk("", "stop") + "data: [DONE]\n\n");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run run = settled(submit(engine));
            assertThat(run.output()).isEqualTo("结论");
            assertThat(store.steps(run.id())).singleElement().extracting(RunStep::reasoning).isEqualTo("先看看🌱");
            assertThat(store.events(run.sessionId(), 0, 256)).filteredOn(event -> event.type().equals("REASONING"))
                    .singleElement().extracting(RunEvent::text).isEqualTo("");
        }
    }

    @Test void fragmentedUtf8MultilineSseAndUsageProduceOneAnswerAndOneRequest() throws Exception {
        serve(exchange -> {
            headers(exchange);
            String text = ": keepalive\r\n\r\ndata: {\"choices\":\r\ndata: [{\"index\":0,\"delta\":{\"reasoning_content\":\"hidden\"}}]}\r\n\r\n"
                    + chunk("你好 🌱", null) + chunk("", "stop")
                    + "data: {\"choices\":[],\"usage\":{}}\n\ndata: [DONE]\n\n";
            for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
                exchange.getResponseBody().write(value);
                exchange.getResponseBody().flush();
            }
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.output()).isEqualTo("你好 🌱");
            assertThat(store.steps(run.id())).singleElement().satisfies(step -> {
                assertThat(step.reasoning()).isEqualTo("hidden");
                assertThat(step.reasoningAt()).isNotNull();
            });
            assertThat(run.provider()).isEqualTo("openai-compatible");
            assertThat(run.model()).isEqualTo("org/example-model:latest");
            assertThat(engine.submit(run.sessionId(), run.requestId(), run.prompt(), "NORMAL").created()).isFalse();
            assertThat(calls).hasValue(1);
            var request = new ObjectMapper().readTree(body.get());
            assertThat(request.path("messages").size()).isEqualTo(1);
            assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(run.prompt());
            assertThat(request.path("stream").asBoolean()).isTrue();
            assertThat(request.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder("model", "messages", "stream");
            assertThat(auth.get()).isEqualTo("Bearer test-only-key");
        }
    }

    @Test void followUpHttpPayloadExactlyMatchesThePersistedConversation() throws Exception {
        serve(exchange -> {
            headers(exchange);
            var incoming = new ObjectMapper().readTree(body.get()).path("messages");
            String answer = incoming.size() == 1 ? "你正在写 Java" : incoming.get(1).path("content").asText();
            write(exchange, chunk(answer, "stop") + "data: [DONE]\n\n");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run first = settled(submit(engine));
            Run second = settled(engine.submit(first.sessionId(), UUID.randomUUID().toString(), "刚才是什么语言？", "NORMAL").run());
            assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(second.output()).isEqualTo(first.output());
            var json = new ObjectMapper();
            assertThat(json.readTree(body.get()).path("messages"))
                    .isEqualTo(json.valueToTree(store.modelRequest(second.id()).messages().stream()
                            .map(message -> java.util.Map.of("role", message.role(), "content", message.content())).toList()));
            assertThat(store.modelRequest(second.id()).messages()).extracting(ModelMessage::content)
                    .containsExactly(first.prompt(), first.output(), second.prompt());
            assertThat(calls).hasValue(2);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"eof", "missing-stop", "length", "content_filter", "tool_calls", "insufficient_system_resource", "malformed", "error", "oversize", "wrong-type"})
    void incompleteAndInvalidStreamsFailWithoutLosingPartialText(String mode) throws Exception {
        serve(exchange -> {
            if (mode.equals("wrong-type")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                write(exchange, "{}");
                return;
            }
            headers(exchange);
            write(exchange, chunk("已保存", null));
            write(exchange, switch (mode) {
                case "eof" -> "";
                case "missing-stop" -> "data: [DONE]\n\n";
                case "malformed" -> "data: {not-json}\n\n";
                case "error" -> "data: {\"error\":{\"message\":\"secret-upstream-body\"}}\n\n";
                case "oversize" -> "data: " + "x".repeat(70_000);
                default -> chunk("", mode) + "data: [DONE]\n\n";
            });
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.output()).isEqualTo(mode.equals("wrong-type") ? "" : "已保存");
            assertThat(run.detail()).doesNotContain("secret", "test-only-key");
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(ints = {401, 402, 429, 500, 302})
    void httpErrorsAreSanitizedAndNeverRetriedOrRedirected(int status) throws Exception {
        serve(exchange -> {
            exchange.getResponseHeaders().set("Location", "/chat/completions");
            exchange.sendResponseHeaders(status, 0);
            write(exchange, "secret-upstream-body");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.detail()).doesNotContain("secret", "test-only-key");
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancelInterruptsStalledHeadersOrBodyAndReleasesSession(boolean sendHeaders) throws Exception {
        var reached = new CountDownLatch(1);
        serve(exchange -> {
            if (sendHeaders) { headers(exchange); write(exchange, chunk("部分内容", null)); }
            reached.countDown();
            new CountDownLatch(1).await();
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(10))) {
            Run run = submit(engine);
            assertThat(reached.await(3, TimeUnit.SECONDS)).isTrue();
            if (sendHeaders) await().atMost(Duration.ofSeconds(3)).until(() -> !store.run(run.id()).output().isEmpty());
            engine.cancel(run.id());
            assertThat(settled(run).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(store.run(run.id()).output()).isEqualTo(sendHeaders ? "部分内容" : "");
            assertThat(engine.submit(run.sessionId(), UUID.randomUUID().toString(), "下一次", "NORMAL").created()).isTrue();
        }
    }

    @Test void deadlineInterruptsStalledBody() throws Exception {
        serve(exchange -> {
            headers(exchange); write(exchange, chunk("超时前输出", null));
            new CountDownLatch(1).await();
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(1))) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.TIMED_OUT);
            assertThat(run.output()).isEqualTo("超时前输出");
        }
    }

    @Test void largeContentChunkIsSplitWithoutBreakingUnicodeOrEventProjection() throws Exception {
        String output = "a".repeat(4095) + "🌱" + "中".repeat(5000);
        serve(exchange -> {
            headers(exchange);
            write(exchange, chunk(output, "stop") + "data: [DONE]\n\n");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.output()).isEqualTo(output);
            var pieces = store.events(run.sessionId(), 0, 256).stream()
                    .filter(event -> event.type().equals("TEXT")).map(RunEvent::text).toList();
            assertThat(pieces).allMatch(piece -> piece.length() <= 4096
                    && !Character.isHighSurrogate(piece.charAt(piece.length() - 1)));
            assertThat(String.join("", pieces)).isEqualTo(output);
        }
    }

    @Test void configurationDoesNotExposeKeyAndRejectsUnsafeEndpoint() {
        try (var model = new OpenAiChatCompletionProvider(URI.create("https://api.openai.com/v1"), "",
                "org/example-model:latest", true, Duration.ofSeconds(2))) {
            assertThat(model.info().configured()).isFalse();
        }
        assertThatThrownBy(() -> new OpenAiChatCompletionProvider(URI.create("http://remote.example"), "test-only-key",
                "org/example-model:latest", true, Duration.ofSeconds(2))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("test-only-key");
    }

    @ParameterizedTest @ValueSource(strings = {"", "/v1", "/gateway/tenant/v1/"})
    void customBasePathsAndKeylessLocalServersUseTheSameProtocol(String prefix) throws Exception {
        String path = prefix.replaceAll("/+$", "");
        serve(path, exchange -> {
            headers(exchange);
            write(exchange, "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[]}}]}\n\n"
                    + chunk("兼容服务回答", "stop") + "data: [DONE]\n\n");
        });
        try (var model = new OpenAiChatCompletionProvider(URI.create("http://127.0.0.1:"
                + upstream.getAddress().getPort() + prefix), "", "local/model:8b@q4", false, Duration.ofSeconds(2));
             var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            assertThat(model.info().configured()).isTrue();
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.output()).isEqualTo("兼容服务回答");
            assertThat(auth.get()).isNull();
            var request = new ObjectMapper().readTree(body.get());
            assertThat(request.path("model").asText()).isEqualTo("local/model:8b@q4");
            assertThat(request.properties()).extracting(java.util.Map.Entry::getKey)
                    .containsExactlyInAnyOrder("model", "messages", "stream");
        }
    }

    @Test void missingModelDisablesSubmissionEvenWhenKeyRequirementIsOff() {
        try (var model = new OpenAiChatCompletionProvider(URI.create("http://localhost:11434/v1"), "", " ",
                false, Duration.ofSeconds(2));
             var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(2))) {
            assertThat(model.info().configured()).isFalse();
            assertThatThrownBy(() -> submit(engine)).isInstanceOf(HarnessException.class).hasMessageContaining("模型配置");
            assertThat(calls).hasValue(0);
        }
    }

    @Test void refusalDeltaIsAnExplicitFailureWithoutPersistingUpstreamReason() {
        serve(exchange -> {
            headers(exchange);
            write(exchange, "data: {\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"private reason\"}}]}\n\n"
                    + chunk("", "stop") + "data: [DONE]\n\n");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.detail()).isEqualTo("模型拒绝回答此请求");
            assertThat(run.output()).isEmpty();
        }
    }

    @Test void historicalProviderIdentitySurvivesConfigurationChangeWithoutReexecution() {
        var session = store.createSession("历史记录");
        Run old = store.submit(session.id(), UUID.randomUUID().toString(), "old", "NORMAL",
                new ModelInfo("deepseek", "deepseek-v4-flash", true)).run();
        store.transition(old.id(), RunStatus.RUNNING, "");
        store.appendText(old.id(), "旧回答");
        store.transition(old.id(), RunStatus.COMPLETED, "");
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5))) {
            var retried = engine.submit(session.id(), old.requestId(), old.prompt(), old.scenario());
            assertThat(retried.created()).isFalse();
            assertThat(retried.run().provider()).isEqualTo("deepseek");
            assertThat(retried.run().output()).isEqualTo("旧回答");
            assertThat(calls).hasValue(0);
        }
    }

    @Test void missingKeyAndUnsupportedScenarioCreateNoRunButOldIdentityCanBeRetried() {
        var session = store.createSession("配置校验");
        var original = store.submit(session.id(), UUID.randomUUID().toString(), "旧模拟输入", "SLOW", TestModelProvider.INFO).run();
        store.transition(original.id(), RunStatus.INTERRUPTED, "restart");
        var missing = new ModelInfo("openai-compatible", "org/example-model:latest", false);
        assertThat(store.submit(session.id(), original.requestId(), original.prompt(), "SLOW", missing).created()).isFalse();
        assertThatThrownBy(() -> store.submit(session.id(), UUID.randomUUID().toString(), "新输入", "NORMAL", missing))
                    .isInstanceOf(HarnessException.class).hasMessageContaining("API Key");
        assertThatThrownBy(() -> store.submit(session.id(), UUID.randomUUID().toString(), "新输入", "FAIL",
                new ModelInfo("openai-compatible", "org/example-model:latest", true))).isInstanceOf(HarnessException.class);
        assertThat(store.snapshot(session.id()).runs()).hasSize(1);
    }

    @Test void migrationPreservesV1RunAndDefaultsItsIdentity() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).target("1").load().migrate();
        var legacy = new JdbcTemplate(ds);
        legacy.update("INSERT INTO harness_session(id,title,created_at) VALUES ('s','legacy',CURRENT_TIMESTAMP)");
        legacy.update("""
                INSERT INTO harness_run(id,session_id,request_id,prompt,scenario,status,output,detail,created_at)
                VALUES ('r','s',?,'old','NORMAL','COMPLETED','existing output','',CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString());
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(legacy.queryForMap("SELECT provider,model,output FROM harness_run WHERE id='r'").values())
                .containsExactlyInAnyOrder("simulated", "local-simulator", "existing output");
    }

    private static String toolChunk(String tools, String finish) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":" + tools
                + "},\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }

    private static String toolDelta(int index, String id, String name, String arguments) throws Exception {
        return new ObjectMapper().writeValueAsString(java.util.Map.of("index", index, "id", id, "type", "function",
                "function", java.util.Map.of("name", name, "arguments", arguments)));
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode wireConversation(com.fasterxml.jackson.databind.JsonNode request) {
        var messages = (com.fasterxml.jackson.databind.node.ArrayNode) request.path("messages").deepCopy();
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.remove(0).path("content").asText()).contains("search_files", "按顺序执行");
        return messages;
    }

    @Test void fragmentedToolCallsLoopThroughRealHttpAndSendStandardPairedMessages(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("App.java"), "class App {}\n");
        serve(exchange -> {
            headers(exchange);
            var request = new ObjectMapper().readTree(body.get());
            assertThat(request.path("tools")).hasSize(3);
            assertThat(request.path("tools").get(0).path("type").asText()).isEqualTo("function");
            if (calls.get() == 1) {
                write(exchange, chunk("正在分析", null)
                        + toolChunk("[" + toolDelta(0, "call_", "read_", "{\"path\":\"") + ","
                                + toolDelta(1, "list", "list_directory", "{}") + "]", null)
                        + toolChunk("[" + toolDelta(0, "read", "file", "App.java\"}") + "]", "tool_calls") + "data: [DONE]\n\n");
            } else {
                var messages = wireConversation(request);
                assertThat(messages).hasSize(4);
                assertThat(messages.get(1).path("tool_calls").get(0).path("function").path("arguments").asText()).isEqualTo("{\"path\":\"App.java\"}");
                assertThat(messages.get(1).has("toolCalls")).isFalse();
                assertThat(messages.get(2).path("role").asText()).isEqualTo("tool");
                assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("call_read");
                assertThat(messages.get(2).path("content").asText()).contains("class App");
                assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("list");
                write(exchange, chunk("项目只有一个 App 类", "stop") + "data: [DONE]\n\n");
            }
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(10), 32000,
                new LocalWorkspaceTools(directory, Duration.ofSeconds(5)), 8)) {
            Run run = settled(submit(engine));
            assertThat(run.status()).as(run.detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.output()).isEqualTo("正在分析项目只有一个 App 类");
            assertThat(calls).hasValue(2);
            assertThat(store.tools(run.id())).extracting(ToolExecution::status).containsExactly("COMPLETED", "COMPLETED");
        }
    }

    @ParameterizedTest @ValueSource(ints = {9, 32})
    void nineCallsAreParsedAndExcessCallsReturnResultsWithoutAborting(int count, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        serve(exchange -> {
            headers(exchange);
            if (calls.get() == 1) {
                for (int i = 0; i < count; i++) {
                    write(exchange, toolChunk("[" + toolDelta(i, "call" + i, "list_directory", "{}") + "]", i == count - 1 ? "tool_calls" : null));
                }
                write(exchange, "data: [DONE]\n\n");
            } else {
                var messages = wireConversation(new ObjectMapper().readTree(body.get()));
                assertThat(messages).hasSize(count + 2);
                assertThat(messages.get(count + 1).path("tool_call_id").asText()).isEqualTo("call" + (count - 1));
                assertThat(messages.get(count + 1).path("content").asText()).contains("未执行", "8");
                write(exchange, chunk("根据已读取的目录完成分析", "stop") + "data: [DONE]\n\n");
            }
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(10), 32000,
                new LocalWorkspaceTools(directory, Duration.ofSeconds(5)), 8)) {
            Run run = settled(submit(engine));
            assertThat(run.status()).as(run.detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(store.tools(run.id())).hasSize(count);
            assertThat(store.tools(run.id()).stream().filter(tool -> tool.status().equals("COMPLETED"))).hasSize(8);
            assertThat(store.tools(run.id()).getLast().status()).isEqualTo("SKIPPED");
            assertThat(calls).hasValue(2);
        }
    }

    @Test void sparseIndexesIdentifyCallsAndDoNotConsumeTheExecutionBudget(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        serve(exchange -> {
            headers(exchange);
            if (calls.get() == 1) {
                write(exchange, toolChunk("[" + toolDelta(100, "second", "list_directory", "{}") + ","
                        + toolDelta(10, "first", "list_directory", "{}") + "]", "tool_calls") + "data: [DONE]\n\n");
            } else write(exchange, chunk("done", "stop") + "data: [DONE]\n\n");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5), 32000,
                new LocalWorkspaceTools(directory, Duration.ofSeconds(5)), 8)) {
            Run run = settled(submit(engine));
            assertThat(run.status()).as(run.detail()).isEqualTo(RunStatus.COMPLETED);
            assertThat(store.tools(run.id())).extracting(ToolExecution::callId).containsExactly("first", "second");
            assertThat(store.tools(run.id())).extracting(ToolExecution::status).containsExactly("COMPLETED", "COMPLETED");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void parsingLimitCountsDistinctCallsAcrossChunksAndRejectsBeforeExecution(boolean fragmented, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        serve(exchange -> {
            headers(exchange);
            var deltas = new java.util.ArrayList<String>();
            for (int i = 0; i < ToolCall.MAX_BATCH_SIZE + 1; i++) deltas.add(toolDelta(i, "call" + i, "list_directory", "{}"));
            if (fragmented) for (String delta : deltas) write(exchange, toolChunk("[" + delta + "]", null));
            else write(exchange, toolChunk("[" + String.join(",", deltas) + "]", "tool_calls"));
            write(exchange, "data: [DONE]\n\n");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5), 32000,
                new LocalWorkspaceTools(directory, Duration.ofSeconds(5)), 8)) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.detail()).contains("解析上限");
            assertThat(store.tools(run.id())).isEmpty();
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"eof", "stop", "missing-id", "duplicate", "index", "oversize", "type", "missing-done", "wrong-field"})
    void invalidToolStreamsNeverExecute(String mode, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        serve(exchange -> {
            headers(exchange);
            String valid = "{\"index\":0,\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"list_directory\",\"arguments\":\"{}\"}}";
            String items = switch (mode) {
                case "missing-id" -> valid.replace("\"id\":\"a\",", "");
                case "duplicate" -> valid + "," + valid.replace("\"index\":0", "\"index\":1");
                case "index" -> valid.replace("\"index\":0", "\"index\":-1");
                case "oversize" -> valid.replace("{}", "x".repeat(16385));
                case "type" -> valid.replace("\"type\":\"function\"", "\"type\":\"custom\"");
                case "wrong-field" -> valid.replace("\"arguments\":\"{}\"", "\"arguments\":{}");
                default -> valid;
            };
            write(exchange, toolChunk("[" + items + "]", mode.equals("eof") ? null : mode.equals("stop") ? "stop" : "tool_calls"));
            if (!mode.equals("eof") && !mode.equals("missing-done")) write(exchange, "data: [DONE]\n\n");
        });
        try (var model = provider(); var engine = new DefaultAgentEngine(store, model, Duration.ofSeconds(5), 32000,
                new LocalWorkspaceTools(directory, Duration.ofSeconds(5)), 8)) {
            Run run = settled(submit(engine));
            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(store.tools(run.id())).isEmpty();
            if (mode.equals("index")) assertThat(run.detail()).contains("index", "非负整数");
            if (mode.equals("type")) assertThat(run.detail()).contains("type", "function");
            assertThat(calls).hasValue(1);
        }
    }
}
