package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.flow.FlowStore;
import dev.javaharness.adapters.flow.FlowStore.Flow;
import dev.javaharness.adapters.flow.FlowStore.Item;
import dev.javaharness.adapters.flow.FlowStore.Pending;
import dev.javaharness.adapters.flow.FlowStore.Step;
import dev.javaharness.core.TokenBudget;
import dev.javaharness.server.service.FlowDefinition.Node;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ContextSummary;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.RunConfiguration;
import dev.javaharness.spi.run.RunConfigurationSource;
import dev.javaharness.spi.session.SessionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 流水线管理：按需求描述调用模型生成流程定义（只生成、不保存，由用户预览后确认），保存与修改流水线，
 * 以及流水线、事项和待办的只读视图。事项的流转由 {@link FlowOrchestrator} 负责。
 */
public final class FlowService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ITEMS = 100;

    /** 生成请求；definition 非空时表示在这份定义上按 description 修改。 */
    public record Design(String description, String modelProfileId, String reasoningEffort, JsonNode definition) {}

    /** 生成结果，尚未保存。 */
    public record Proposal(String name, FlowDefinition definition) {}

    /** 保存请求；definition 为空时保留原定义（只改名称、说明或工作区）。 */
    public record Draft(String name, String description, String workspaceId, JsonNode definition, long revision) {}

    public record FlowView(
            String id,
            String name,
            String description,
            String workspaceId,
            FlowDefinition definition,
            Instant createdAt,
            long revision) {}

    /** 列表项；waiting 含岗位运行中等待审批或回答的事项，version 变化表示这条流水线的视图可能已变化。 */
    public record Summary(FlowView flow, int running, int waiting, String version) {}

    /**
     * 事项概要，不含输入正文与流程快照；pending 在岗位运行中等待用户时为 APPROVAL（待审批）或 ANSWER（待回答），
     * 否则为空。
     */
    public record ItemView(
            String id,
            String flowId,
            String flowName,
            String title,
            String status,
            String nodeKey,
            String detail,
            int steps,
            String pending,
            Instant createdAt,
            Instant updatedAt) {}

    public record Snapshot(FlowView flow, List<ItemView> items, String version) {}

    /** 事项详情；options 为等待用户处理时可选的分支标签（单一出口时为一个空标签），retryable 表示可重试当前岗位。 */
    public record ItemDetail(
            ItemView item,
            String input,
            String sessionId,
            FlowDefinition definition,
            List<Step> steps,
            List<String> options,
            boolean retryable) {}

    private final FlowStore flows;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final SettingsService settings;
    private final RunConfigurationSource configurations;
    private ExternalAgentCatalog externalAgents;

    public FlowService(
            FlowStore flows,
            SessionStore sessions,
            TransactionTemplate tx,
            SettingsService settings,
            RunConfigurationSource configurations) {
        this.flows = flows;
        this.sessions = sessions;
        this.tx = tx;
        this.settings = settings;
        this.configurations = configurations;
    }

    /** 绑定外部智能体目录，校验岗位引用的 ACP 智能体是否存在。 */
    public void useExternalAgents(ExternalAgentCatalog externalAgents) {
        this.externalAgents = externalAgents;
    }

    public List<Summary> list() {
        List<Pending> pending = flows.pending();
        return flows.list().stream()
                .map(flow -> {
                    var counts = flows.counts(flow.id());
                    int blocked = (int) pending.stream()
                            .filter(value -> value.flowId().equals(flow.id()))
                            .count();
                    return new Summary(
                            view(flow),
                            Math.max(0, counts.running() - blocked),
                            counts.waiting() + blocked,
                            version(flow.id(), pending));
                })
                .toList();
    }

    /** Approvals and questions arise inside a run without touching the flow, so they are part of the version. */
    private String version(String flowId, List<Pending> pending) {
        var text = new StringBuilder().append(flows.lastSeq(flowId));
        for (Pending value : pending)
            if (value.flowId().equals(flowId))
                text.append(':').append(value.itemId()).append(value.kind().charAt(1));
        return text.toString();
    }

    private static String kind(String itemId, List<Pending> pending) {
        return pending.stream()
                .filter(value -> value.itemId().equals(itemId))
                .map(Pending::kind)
                .findFirst()
                .orElse("");
    }

    public Snapshot snapshot(String id) {
        synchronized (sessions) {
            return tx.execute(status -> {
                Flow flow = flows.get(id);
                List<Pending> pending = flows.pending();
                return new Snapshot(
                        view(flow),
                        flows.items(id, ITEMS).stream()
                                .map(item -> view(item, flow.name(), kind(item.id(), pending)))
                                .toList(),
                        version(id, pending));
            });
        }
    }

    /**
     * Items of all flows waiting for the user, oldest first: parked at a node, or travelling with an approval or
     * a question open in the running agent.
     */
    public List<ItemView> inbox() {
        synchronized (sessions) {
            return tx.execute(status -> {
                List<Pending> pending = flows.pending();
                var items = new ArrayList<>(flows.waiting());
                for (Pending value : pending) items.add(flows.item(value.itemId()));
                return items.stream()
                        .sorted(Comparator.comparing(Item::updatedAt).thenComparing(Item::id))
                        .map(item -> view(item, flows.get(item.flowId()).name(), kind(item.id(), pending)))
                        .toList();
            });
        }
    }

    public ItemDetail item(String id) {
        synchronized (sessions) {
            return tx.execute(status -> {
                Item item = flows.item(id);
                FlowDefinition definition = FlowDefinition.parse(item.definition());
                boolean waiting = item.status().equals("WAITING");
                Node node = definition.node(item.nodeKey());
                return new ItemDetail(
                        view(item, flows.get(item.flowId()).name(), kind(id, flows.pending())),
                        item.input(),
                        item.sessionId(),
                        definition,
                        flows.steps(id),
                        waiting
                                ? definition.outgoing(item.nodeKey()).stream()
                                        .map(FlowDefinition.Edge::label)
                                        .toList()
                                : List.of(),
                        waiting && node != null && node.type().equals("AGENT"));
            });
        }
    }

    public Snapshot create(Draft draft) {
        if (draft.definition() == null || draft.definition().isNull()) throw new HarnessException("INVALID", "请先生成流程");
        Flow value = validated("", draft, null);
        String id = write(() -> flows.save(value)).id();
        return snapshot(id);
    }

    /** Items already travelling keep the definition they started with. */
    public Snapshot update(String id, Draft draft) {
        Flow value = validated(id, draft, flows.get(id));
        write(() -> flows.save(value));
        return snapshot(id);
    }

    /** Item sessions are driven only by their flow; reading them stays allowed. */
    public void requireOrdinary(String sessionId) {
        if (flows.itemBySession(sessionId) != null) throw new HarnessException("CONFLICT", "这是流水线事项的会话，请在流水线中处理");
    }

    private Flow validated(String id, Draft draft, Flow existing) {
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.isEmpty() || name.length() > 100) throw new HarnessException("INVALID", "流水线名称需为 1–100 个字符");
        String description =
                draft.description() == null ? "" : draft.description().strip();
        if (description.length() > 2000) throw new HarnessException("INVALID", "需求描述不能超过 2000 个字符");
        String workspaceId = draft.workspaceId() == null ? "" : draft.workspaceId();
        if (!workspaceId.isEmpty() && (existing == null || !workspaceId.equals(existing.workspaceId())))
            sessions.workspace(workspaceId);
        String definition;
        if (draft.definition() == null || draft.definition().isNull()) definition = existing.definition();
        else {
            FlowDefinition parsed = parse(draft.definition());
            FlowDefinition previous = existing == null ? null : FlowDefinition.parse(existing.definition());
            for (Node node : parsed.nodes()) {
                if (!node.type().equals("AGENT")) continue;
                Node old = previous == null ? null : previous.node(node.key());
                if (!node.externalAgentId().isEmpty()) {
                    if (old == null || !node.externalAgentId().equals(old.externalAgentId()))
                        externalAgent(node.externalAgentId());
                } else {
                    profile(node.modelProfileId());
                }
            }
            definition = parsed.json();
        }
        return new Flow(
                id,
                name,
                description,
                workspaceId,
                definition,
                existing == null ? null : existing.createdAt(),
                draft.revision());
    }

    private static FlowDefinition parse(JsonNode definition) {
        try {
            return FlowDefinition.parse(definition);
        } catch (IllegalArgumentException error) {
            throw new HarnessException("INVALID", error.getMessage());
        }
    }

    /** A missing model profile is an invalid choice here, not a missing resource. */
    private void profile(String id) {
        try {
            settings.profile(id);
        } catch (HarnessException error) {
            if ("NOT_FOUND".equals(error.code())) throw new HarnessException("INVALID", "岗位所选的模型配置不存在，请重新选择");
            throw error;
        }
    }

    private void externalAgent(String id) {
        if (externalAgents == null || externalAgents.get(id) == null)
            throw new HarnessException("INVALID", "岗位所选的外部智能体不存在，请重新选择");
    }

    /**
     * Ask the model to design a flow for the requirement, or to change the given definition. An invalid answer is
     * sent back once with the reason. Agents of a changed flow keep their settings; new agents use the chosen model.
     */
    public Proposal design(Design request) {
        String description =
                request.description() == null ? "" : request.description().strip();
        if (description.isEmpty() || description.length() > 2000)
            throw new HarnessException("INVALID", "需求描述需为 1–2000 个字符");
        boolean change = request.definition() != null && !request.definition().isNull();
        FlowDefinition base = change ? parse(request.definition()) : null;
        String profile =
                request.modelProfileId() == null || request.modelProfileId().isBlank()
                        ? "default"
                        : request.modelProfileId();
        RunConfiguration configuration =
                configurations.snapshot(new ModelSelection(profile, request.reasoningEffort()));
        if (!configuration.modelInfo().configured()) throw new HarnessException("INVALID", "所选模型未配置完成，请先在设置中配置模型");
        String error = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String text;
            try {
                text = complete(configuration, FlowPrompts.designPrompt(description, base, error));
            } catch (HarnessException failure) {
                throw failure;
            } catch (InterruptedException failure) {
                throw new HarnessException("UNAVAILABLE", "生成超时，请重试");
            } catch (Exception failure) {
                throw new HarnessException("UNAVAILABLE", "生成失败，请检查模型连接后重试");
            }
            try {
                int start = text.indexOf('{');
                int end = text.lastIndexOf('}');
                if (start < 0 || end < start) throw new IllegalArgumentException("没有输出 JSON");
                JsonNode root;
                try {
                    root = JSON.readTree(text.substring(start, end + 1));
                } catch (Exception failure) {
                    throw new IllegalArgumentException("输出的 JSON 无法解析");
                }
                FlowDefinition parsed = FlowDefinition.parse(root);
                var nodes = new ArrayList<Node>();
                for (Node node : parsed.nodes()) {
                    Node kept = base == null ? null : base.node(node.key());
                    if (!node.type().equals("AGENT")) nodes.add(node);
                    else if (kept != null && kept.type().equals("AGENT"))
                        nodes.add(new Node(
                                node.key(),
                                "AGENT",
                                node.name(),
                                node.description(),
                                kept.modelProfileId(),
                                kept.reasoningEffort(),
                                kept.approvalMode(),
                                kept.externalAgentId()));
                    else
                        nodes.add(new Node(
                                node.key(), "AGENT", node.name(), node.description(), profile, null, "MANUAL", ""));
                }
                String name = root.path("name").asText("").strip();
                return new Proposal(
                        name.isEmpty() ? "新流水线" : FlowPrompts.clip(name, 99),
                        new FlowDefinition(List.copyOf(nodes), parsed.edges()));
            } catch (IllegalArgumentException invalid) {
                error = invalid.getMessage();
            }
        }
        throw new HarnessException("INVALID", "模型生成的流程不符合规则（" + error + "），请调整描述后重试");
    }

    /** One tool-free request with the chosen model; it must answer within the profile's run timeout. */
    private String complete(RunConfiguration configuration, String prompt) throws Exception {
        String instructions = FlowPrompts.designInstructions();
        long deadline = System.nanoTime() + configuration.timeout().toNanos();
        ModelProvider.Cancellation cancellation = () -> {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new InterruptedException("design stopped");
        };
        try (RunConfiguration.Resources resources = configuration.resources().open("")) {
            var message = new ModelMessage("user", prompt);
            var request = new ModelRequest(
                    UUID.randomUUID().toString(),
                    configuration.modelInfo().model(),
                    "NORMAL",
                    List.of(message),
                    new ContextSummary(
                            0,
                            0,
                            0,
                            prompt.length(),
                            0,
                            new TokenBudget(configuration.tokenCounter()).message(message),
                            configuration.contextWindowTokens()),
                    1,
                    "",
                    List.of(),
                    instructions,
                    false,
                    configuration.selection().reasoningEffort());
            String text = resources
                    .model()
                    .complete(request, cancellation, chunk -> {})
                    .text();
            cancellation.check();
            return text;
        }
    }

    private static FlowView view(Flow flow) {
        return new FlowView(
                flow.id(),
                flow.name(),
                flow.description(),
                flow.workspaceId(),
                FlowDefinition.parse(flow.definition()),
                flow.createdAt(),
                flow.revision());
    }

    private static ItemView view(Item item, String flowName, String pending) {
        return new ItemView(
                item.id(),
                item.flowId(),
                flowName,
                item.title(),
                item.status(),
                item.nodeKey(),
                item.detail(),
                item.steps(),
                pending,
                item.createdAt(),
                item.updatedAt());
    }

    private <T> T write(java.util.function.Supplier<T> change) {
        synchronized (sessions) {
            return tx.execute(status -> change.get());
        }
    }
}
