package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:flow-api;DB_CLOSE_DELAY=-1",
            "harness.model.base-url=https://api.openai.com/v1",
            "harness.model.name=",
            "harness.model.api-key=",
            "harness.session-naming.enabled=false",
            "harness.automations.enabled=false"
        })
class FlowApiIntegrationTest {
    private static final Pattern AGENT = Pattern.compile("担任岗位「([^」]+)」");
    private static final String FAIL = "!fail";
    // start → 审查员 —通过→ 确认(你) → 已完成；审查员 —打回→ 修改员 → 审查员；审查员 —驳回→ 已驳回
    private static final String DEFINITION = """
            {"nodes":[
              {"key":"start","type":"TRIGGER","name":"触发","description":"新需求"},
              {"key":"review","type":"AGENT","name":"审查员","description":"审查需求是否清楚"},
              {"key":"fix","type":"AGENT","name":"修改员","description":"按审查意见修改"},
              {"key":"confirm","type":"HUMAN","name":"确认","description":"确认结果"},
              {"key":"done","type":"END","name":"已完成","description":""},
              {"key":"rejected","type":"END","name":"已驳回","description":""}],
             "edges":[
              {"from":"start","to":"review","label":""},
              {"from":"review","to":"confirm","label":"通过"},
              {"from":"review","to":"fix","label":"打回"},
              {"from":"review","to":"rejected","label":"驳回"},
              {"from":"fix","to":"review","label":""},
              {"from":"confirm","to":"done","label":"同意"},
              {"from":"confirm","to":"fix","label":"再改改"}]}
            """;

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "harness.settings.key-file",
                () -> directory.resolve("settings.key").toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** One upstream call: who was asked (DESIGN or an agent node name) and the user message it received. */
    record Call(String speaker, String content) {}

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, String> script = call -> "结论：通过";
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private HttpServer upstream;

    @BeforeEach
    void model() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                JsonNode messages = json.readTree(exchange.getRequestBody()).path("messages");
                String system = messages.path(0).path("content").asText();
                String content =
                        messages.path(messages.size() - 1).path("content").asText();
                var matcher = AGENT.matcher(content);
                String speaker = system.contains("流程设计师") ? "DESIGN" : matcher.find() ? matcher.group(1) : "?";
                var call = new Call(speaker, content);
                calls.add(call);
                if (!speaker.equals("DESIGN")) hold.await(20, TimeUnit.SECONDS);
                String reply = script.apply(call);
                if (reply.equals(FAIL)) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                String data = json.writeValueAsString(Map.of(
                        "choices",
                        List.of(Map.of("index", 0, "delta", Map.of("content", reply), "finish_reason", "stop"))));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody()
                        .write(("data: " + data + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.start();
        var config = (ObjectNode) get("/settings");
        config.put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1")
                .put("modelName", "flow-model")
                .put("apiKeyRequired", false)
                .put("apiKey", "")
                .put("runTimeoutSeconds", 45);
        assertThat(post("/settings", config).statusCode()).isEqualTo(200);
    }

    @AfterEach
    void stop() {
        hold.countDown();
        upstream.stop(0);
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api" + path);
    }

    HttpResponse<String> post(String path, Object body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode get(String path) throws Exception {
        return json.readTree(
                client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())
                        .body());
    }

    String flow() throws Exception {
        var draft = new HashMap<String, Object>();
        draft.put("name", "需求评审");
        draft.put("description", "评审每一条新需求");
        draft.put("definition", json.readTree(DEFINITION));
        var response = post("/flows", draft);
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("flow").path("id").asText();
    }

    String start(String flow, String title) throws Exception {
        var response = post("/flows/" + flow + "/items", Map.of("title", title, "input", "支持导出 CSV"));
        assertThat(response.statusCode()).isEqualTo(202);
        return json.readTree(response.body()).path("id").asText();
    }

    /** Wait until the item has the status, then return its detail. */
    JsonNode until(String item, String status) {
        var result = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode detail = get("/flow-items/" + item);
            assertThat(detail.path("item").path("status").asText()).isEqualTo(status);
            result[0] = detail;
        });
        return result[0];
    }

    /** Each step as node:status:outcome. */
    static List<String> route(JsonNode detail) {
        var lines = new ArrayList<String>();
        for (JsonNode step : detail.path("steps"))
            lines.add(step.path("nodeName").asText() + ":"
                    + step.path("status").asText() + ":"
                    + step.path("outcome").asText());
        return lines;
    }

    List<String> speakers() {
        return calls.stream().map(Call::speaker).toList();
    }

    @Test
    void designsAFlowAndRetriesAnInvalidAnswerOnce() throws Exception {
        var answers = new ArrayList<>(List.of(
                "我想想……{\"nodes\":[],\"edges\":[]}",
                "```json\n{\"name\":\"需求评审\"," + DEFINITION.strip().substring(1) + "\n```"));
        script = call -> answers.removeFirst();
        int saved = get("/flows").size();

        var response = post("/flows/design", Map.of("description", "评审每一条新需求"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode proposal = json.readTree(response.body());
        assertThat(proposal.path("name").asText()).isEqualTo("需求评审");
        assertThat(proposal.path("definition").path("nodes")).hasSize(6);
        assertThat(proposal.path("definition")
                        .path("nodes")
                        .path(1)
                        .path("modelProfileId")
                        .asText())
                .isEqualTo("default");
        assertThat(speakers()).containsExactly("DESIGN", "DESIGN");
        assertThat(calls.get(1).content()).contains("不符合规则").contains("3–12 个节点");
        // Nothing is saved until the proposal is posted back.
        assertThat(get("/flows")).hasSize(saved);

        // Two invalid answers end the design with the reason.
        script = call -> "{}";
        var failed = post("/flows/design", Map.of("description", "评审每一条新需求"));
        assertThat(failed.statusCode()).isEqualTo(400);
        assertThat(failed.body()).contains("不符合规则");
    }

    @Test
    void anItemFollowsVerdictsWaitsForTheUserAndCompletes() throws Exception {
        String flow = flow();
        var replies = new ArrayList<>(List.of("需求不清楚。\n结论：打回", "已补充验收标准。", "现在清楚了。\n结论：通过"));
        script = call -> replies.removeFirst();

        String item = start(flow, "导出报表");
        JsonNode waiting = until(item, "WAITING");

        assertThat(route(waiting)).containsExactly("审查员:DONE:打回", "修改员:DONE:", "审查员:DONE:通过", "确认:WAITING:");
        assertThat(waiting.path("options")).extracting(JsonNode::asText).containsExactly("同意", "再改改");
        assertThat(waiting.path("retryable").asBoolean()).isFalse();
        assertThat(speakers()).containsExactly("审查员", "修改员", "审查员");
        // The second review sees the route so far.
        assertThat(calls.get(2).content()).contains("1. 审查员 → 打回").contains("2. 修改员");
        assertThat(get("/flow-items/waiting"))
                .extracting(node -> node.path("id").asText())
                .contains(item);
        JsonNode summary = get("/flows").path(0);
        assertThat(summary.path("waiting").asInt()).isEqualTo(1);

        // The item session is hidden and cannot be driven as an ordinary session.
        String session = waiting.path("sessionId").asText();
        assertThat(get("/sessions"))
                .extracting(node -> node.path("id").asText())
                .doesNotContain(session);
        assertThat(post(
                                "/sessions/" + session + "/runs",
                                Map.of("requestId", UUID.randomUUID().toString(), "prompt", "插一句"))
                        .statusCode())
                .isEqualTo(409);

        assertThat(post("/flow-items/" + item + "/decide", Map.of("label", "没有这个选项"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(post("/flow-items/" + item + "/decide", Map.of("label", "同意", "note", "可以上线"))
                        .statusCode())
                .isEqualTo(200);
        JsonNode done = until(item, "COMPLETED");
        assertThat(route(done)).endsWith("确认:DONE:同意", "已完成:DONE:");
        assertThat(done.path("item").path("detail").asText()).isEqualTo("已完成");
        assertThat(done.path("steps").path(3).path("summary").asText()).isEqualTo("可以上线");
        assertThat(post("/flow-items/" + item + "/decide", Map.of("label", "同意"))
                        .statusCode())
                .isEqualTo(409);
    }

    @Test
    void aMissingVerdictOrAFailedRunWaitsForTheUser() throws Exception {
        String flow = flow();
        script = call -> "我说不好。";

        String item = start(flow, "模糊需求");
        JsonNode waiting = until(item, "WAITING");

        assertThat(route(waiting)).containsExactly("审查员:WAITING:");
        assertThat(waiting.path("item").path("detail").asText()).contains("没有给出明确结论");
        assertThat(waiting.path("steps").path(0).path("summary").asText()).isEqualTo("我说不好。");
        assertThat(waiting.path("retryable").asBoolean()).isTrue();

        // Retrying runs the same node again; a failed run waits as well.
        script = call -> FAIL;
        assertThat(post("/flow-items/" + item + "/retry", Map.of()).statusCode())
                .isEqualTo(200);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() ->
                        assertThat(route(get("/flow-items/" + item))).containsExactly("审查员:FAILED:", "审查员:FAILED:"));
        assertThat(until(item, "WAITING").path("item").path("detail").asText()).contains("未完成");

        // The user decides for the agent.
        assertThat(post("/flow-items/" + item + "/decide", Map.of("label", "驳回", "note", "不做"))
                        .statusCode())
                .isEqualTo(200);
        JsonNode done = until(item, "COMPLETED");
        assertThat(route(done)).containsExactly("审查员:FAILED:", "审查员:DONE:驳回", "已驳回:DONE:");
        assertThat(done.path("steps").path(1).path("detail").asText()).isEqualTo("由用户决定：不做");
    }

    @Test
    void stoppingCancelsTheRunAndDeletingRemovesTheSessions() throws Exception {
        String flow = flow();
        hold = new CountDownLatch(1);

        String item = start(flow, "长任务");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(speakers()).containsExactly("审查员"));
        assertThat(get("/flows").path(0).path("running").asInt()).isEqualTo(1);
        assertThat(post("/flows/" + flow + "/delete", Map.of()).statusCode()).isEqualTo(409);
        assertThat(post("/flow-items/" + item + "/delete", Map.of()).statusCode())
                .isEqualTo(409);

        assertThat(post("/flow-items/" + item + "/stop", Map.of()).statusCode()).isEqualTo(200);
        hold.countDown();
        JsonNode stopped = until(item, "STOPPED");
        assertThat(route(stopped)).containsExactly("审查员:CANCELLED:");
        String session = stopped.path("sessionId").asText();
        // The cancelled run settles without moving the stopped item.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(get("/sessions/" + session)
                                .path("runs")
                                .path(0)
                                .path("status")
                                .asText())
                        .isEqualTo("CANCELLED"));
        assertThat(until(item, "STOPPED").path("steps")).hasSize(1);

        assertThat(post("/flows/" + flow + "/delete", Map.of()).statusCode()).isEqualTo(200);
        assertThat(get("/flows"))
                .extracting(node -> node.path("flow").path("id").asText())
                .doesNotContain(flow);
        assertThat(client.send(
                                HttpRequest.newBuilder(uri("/sessions/" + session))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void anApprovalOrQuestionInsideARunningAgentReachesTheInbox() throws Exception {
        String flow = flow();
        hold = new CountDownLatch(1);
        String item = start(flow, "要审批");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(speakers()).containsExactly("审查员"));
        JsonNode running = get("/flow-items/" + item);
        assertThat(running.path("item").path("pending").asText()).isEmpty();
        assertThat(get("/flow-items/waiting"))
                .extracting(node -> node.path("id").asText())
                .doesNotContain(item);
        String version = summary(flow).path("version").asText();
        String run = running.path("steps").path(0).path("runId").asText();

        String question = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO harness_question(id,run_id,call_id,question,options,multi_select,status,created_at)
                VALUES (?,?,?,'选择方案','[]',false,'PENDING',CURRENT_TIMESTAMP)
                """, question, run, question);
        assertThat(get("/flow-items/" + item).path("item").path("pending").asText())
                .isEqualTo("ANSWER");
        assertThat(get("/flow-items/waiting"))
                .filteredOn(node -> node.path("id").asText().equals(item))
                .extracting(node -> node.path("pending").asText())
                .containsExactly("ANSWER");
        JsonNode summary = summary(flow);
        assertThat(summary.path("running").asInt()).isZero();
        assertThat(summary.path("waiting").asInt()).isEqualTo(1);
        // The page rereads the flow although nothing in the flow itself changed.
        assertThat(summary.path("version").asText()).isNotEqualTo(version);
        assertThat(get("/flows/" + flow).path("version").asText())
                .isEqualTo(summary.path("version").asText());

        // An approval outranks a question.
        String command = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO harness_command(id,run_id,call_id,command_text,directory,shell,purpose,timeout_seconds,status,created_at) VALUES (?,?,?,'dir','.','powershell','看看',30,'PENDING',CURRENT_TIMESTAMP)",
                command,
                run,
                command);
        assertThat(get("/flows/" + flow).path("items").path(0).path("pending").asText())
                .isEqualTo("APPROVAL");
        assertThat(summary(flow).path("version").asText())
                .isNotEqualTo(summary.path("version").asText());

        // Once handled, or once the item no longer travels, it leaves the inbox.
        jdbc.update("UPDATE harness_command SET status='REJECTED' WHERE id=?", command);
        assertThat(get("/flow-items/" + item).path("item").path("pending").asText())
                .isEqualTo("ANSWER");
        // Answering stays possible in the item session although the session itself cannot be driven.
        assertThat(post("/questions/" + question + "/skip", Map.of()).statusCode())
                .isEqualTo(200);
        assertThat(get("/flow-items/" + item).path("item").path("pending").asText())
                .isEmpty();
        jdbc.update("UPDATE harness_question SET status='PENDING' WHERE id=?", question);
        assertThat(post("/flow-items/" + item + "/stop", Map.of()).statusCode()).isEqualTo(200);
        assertThat(get("/flow-items/" + item).path("item").path("pending").asText())
                .isEmpty();
        assertThat(get("/flow-items/waiting"))
                .extracting(node -> node.path("id").asText())
                .doesNotContain(item);
        assertThat(summary(flow).path("waiting").asInt()).isZero();
    }

    @Test
    void theReadyMadePullRequestFlowCanBeUsedAsItIs() throws Exception {
        String flow = "00000000-0000-4000-8000-0000000f10a1";
        JsonNode definition = summary(flow).path("flow").path("definition");
        assertThat(summary(flow).path("flow").path("name").asText()).isEqualTo("PR 流水线");
        // The stored text is what saving the definition would write, so the first edit changes nothing else.
        assertThat(jdbc.queryForObject("SELECT definition FROM harness_flow WHERE id=?", String.class, flow))
                .isEqualTo(json.writeValueAsString(definition));
        script = call -> call.speaker().equals("风险评估员") ? "改动局部。\n结论：高风险" : "结论：通过";

        String item = start(flow, "修复导出乱码");
        JsonNode waiting = until(item, "WAITING");

        assertThat(route(waiting)).containsExactly("门禁检查员:DONE:通过", "代码审查员:DONE:通过", "风险评估员:DONE:高风险", "人工验收:WAITING:");
        assertThat(waiting.path("options")).extracting(JsonNode::asText).containsExactly("合并", "打回");
        assertThat(post("/flow-items/" + item + "/decide", Map.of("label", "合并"))
                        .statusCode())
                .isEqualTo(200);
        assertThat(until(item, "COMPLETED").path("item").path("detail").asText())
                .isEqualTo("可以合并");
    }

    @Test
    void builtInNodeReceivesPriorAcpStepSummaryInline() throws Exception {
        var definition = Map.of(
                "nodes",
                List.of(
                        Map.of("key", "start", "type", "TRIGGER", "name", "开始", "description", ""),
                        Map.of("key", "first", "type", "AGENT", "name", "前序岗位", "description", "先分析问题"),
                        Map.of("key", "check", "type", "HUMAN", "name", "人工中转", "description", "确认流转"),
                        Map.of("key", "second", "type", "AGENT", "name", "内置汇总", "description", "汇总前序结论"),
                        Map.of("key", "done", "type", "END", "name", "完成", "description", "")),
                "edges",
                List.of(
                        Map.of("from", "start", "to", "first", "label", ""),
                        Map.of("from", "first", "to", "check", "label", ""),
                        Map.of("from", "check", "to", "second", "label", "继续"),
                        Map.of("from", "second", "to", "done", "label", "")));
        var created = post(
                "/flows",
                Map.of("name", "跨执行器交接", "description", "测试 ACP 到内置模型交接", "workspaceId", "", "definition", definition));
        assertThat(created.statusCode()).isEqualTo(201);
        String flowId = json.readTree(created.body()).path("flow").path("id").asText();
        var secondPrompt = new java.util.concurrent.atomic.AtomicReference<String>("");
        script = call -> {
            if (call.speaker().equals("内置汇总")) {
                secondPrompt.set(call.content());
                return "汇总完成。";
            }
            return "ACP 发现关键根因：连接池泄漏。";
        };
        String item = start(flowId, "排查线上超时");
        JsonNode paused = until(item, "WAITING");
        String firstRunId = paused.path("steps").path(0).path("runId").asText();
        // 模拟前序岗位由 ACP 执行（内置模型的 contextHistory 会跳过 scenario='ACP'）。
        jdbc.update("UPDATE harness_run SET scenario='ACP' WHERE id=?", firstRunId);
        assertThat(post("/flow-items/" + item + "/decide", Map.of("label", "继续"))
                        .statusCode())
                .isEqualTo(200);
        until(item, "COMPLETED");
        assertThat(secondPrompt.get()).contains("<step_output>").contains("ACP 发现关键根因：连接池泄漏。");
    }

    /** The list entry of one flow;the database is shared by the tests of this class. */
    JsonNode summary(String flow) throws Exception {
        for (JsonNode entry : get("/flows"))
            if (entry.path("flow").path("id").asText().equals(flow)) return entry;
        throw new AssertionError("flow not listed");
    }

    @Test
    void anEndlessLoopFailsAtTheStepLimitAndEditsKeepRunningItemsOnTheirSnapshot() throws Exception {
        String flow = flow();
        hold = new CountDownLatch(1);
        script = call -> call.speaker().equals("审查员") ? "结论：打回" : "改好了。";

        String item = start(flow, "改不完");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(speakers()).containsExactly("审查员"));
        // Renaming a node now does not rename it for the travelling item.
        JsonNode snapshot = get("/flows/" + flow);
        var draft = new HashMap<String, Object>();
        draft.put("name", "需求评审 2");
        draft.put("definition", json.readTree(DEFINITION.replace("修改员", "返工员")));
        draft.put("revision", snapshot.path("flow").path("revision").asLong());
        assertThat(post("/flows/" + flow, draft).statusCode()).isEqualTo(200);
        assertThat(post("/flows/" + flow, draft).statusCode()).isEqualTo(409);
        hold.countDown();

        // Forty serial runs; slow machines need more than the usual wait.
        var failure = new JsonNode[1];
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            failure[0] = get("/flow-items/" + item);
            assertThat(failure[0].path("item").path("status").asText()).isEqualTo("FAILED");
        });
        JsonNode failed = failure[0];
        assertThat(failed.path("item").path("detail").asText()).contains("最多 40 步");
        assertThat(failed.path("steps")).hasSize(40);
        assertThat(speakers()).contains("修改员").doesNotContain("返工员");
        assertThat(get("/flows/" + flow)
                        .path("flow")
                        .path("definition")
                        .path("nodes")
                        .path(2)
                        .path("name")
                        .asText())
                .isEqualTo("返工员");
    }
}
