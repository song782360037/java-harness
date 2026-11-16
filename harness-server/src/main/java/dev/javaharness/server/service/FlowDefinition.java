package dev.javaharness.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.ApprovalMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 流水线的流程定义：节点（TRIGGER 触发、AGENT 岗位、HUMAN 人工、END 终点）与带分支标签的连线。
 * 解析时完成全部结构校验，因此拿到的定义总是可以流转到终点的。
 */
public record FlowDefinition(List<Node> nodes, List<Edge> edges) {
    public static final int MAX_NODES = 12;
    private static final int MAX_NAME = 20;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_LABEL = 12;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    // Decoration a model may put around a verdict; labels are compared without it.
    private static final String NOISE = "[\\s*`_「」【】\"'“”。.！!]";
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Set<String> TYPES = Set.of("TRIGGER", "AGENT", "HUMAN", "END");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一个节点；模型配置、思考强度和审批模式只对 AGENT 有意义，其余类型为空。 */
    public record Node(
            String key,
            String type,
            String name,
            String description,
            String modelProfileId,
            String reasoningEffort,
            String approvalMode) {}

    /** 一条连线；节点有多条出边时 label 是区分分支的结论。 */
    public record Edge(String from, String to, String label) {}

    /** A branch label as it is compared with an agent's verdict. */
    static String plain(String label) {
        return label.replaceAll(NOISE, "");
    }

    public Node node(String key) {
        return nodes.stream().filter(node -> node.key().equals(key)).findFirst().orElse(null);
    }

    public Node trigger() {
        return nodes.stream().filter(node -> node.type().equals("TRIGGER")).findFirst().orElseThrow();
    }

    /** Outgoing edges of a node in definition order. */
    public List<Edge> outgoing(String key) {
        return edges.stream().filter(edge -> edge.from().equals(key)).toList();
    }

    public String json() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** Parse stored or submitted JSON text; see {@link #parse(JsonNode)}. */
    public static FlowDefinition parse(String text) {
        try {
            return parse(JSON.readTree(text));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("流程定义不是有效的 JSON");
        }
    }

    /**
     * Parse and validate a definition: one trigger with a single exit, distinct branch labels, every node reachable
     * from the trigger and able to reach an end. Throws IllegalArgumentException with a message for the user.
     */
    public static FlowDefinition parse(JsonNode root) {
        if (root == null || !root.path("nodes").isArray() || !root.path("edges").isArray())
            throw new IllegalArgumentException("流程定义需要包含 nodes 和 edges 数组");
        var nodes = new ArrayList<Node>();
        var types = new HashMap<String, String>();
        for (JsonNode value : root.path("nodes")) {
            String key = value.path("key").asText("").strip();
            if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("节点 key 无效：" + key);
            String type = value.path("type").asText("").strip().toUpperCase(java.util.Locale.ROOT);
            if (!TYPES.contains(type)) throw new IllegalArgumentException("节点 " + key + " 的类型无效");
            if (types.put(key, type) != null) throw new IllegalArgumentException("节点 key 重复：" + key);
            String name = value.path("name").asText("").strip();
            if (name.isEmpty() || name.length() > MAX_NAME || CONTROL.matcher(name).find())
                throw new IllegalArgumentException("节点 " + key + " 的名称需为 1–" + MAX_NAME + " 个字符");
            String description = value.path("description").asText("").strip();
            if (description.length() > MAX_DESCRIPTION)
                throw new IllegalArgumentException("节点「" + name + "」的说明不能超过 " + MAX_DESCRIPTION + " 个字符");
            if (!type.equals("AGENT")) {
                nodes.add(new Node(key, type, name, description, "", null, ""));
                continue;
            }
            if (description.isEmpty()) throw new IllegalArgumentException("岗位「" + name + "」缺少职责说明");
            String profile = value.path("modelProfileId").asText("").strip();
            String effort = value.path("reasoningEffort").isTextual()
                    ? value.path("reasoningEffort").asText()
                    : null;
            if (effort != null && !ModelSelection.EFFORTS.contains(effort))
                throw new IllegalArgumentException("岗位「" + name + "」的思考强度无效");
            String approval = value.path("approvalMode").asText("").strip();
            try {
                approval = approval.isEmpty() ? ApprovalMode.MANUAL.name() : ApprovalMode.parse(approval).name();
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("岗位「" + name + "」的审批模式无效");
            }
            nodes.add(new Node(key, type, name, description, profile.isEmpty() ? "default" : profile, effort, approval));
        }
        if (nodes.size() < 3 || nodes.size() > MAX_NODES)
            throw new IllegalArgumentException("流程需要 3–" + MAX_NODES + " 个节点");
        if (nodes.stream().filter(node -> node.type().equals("TRIGGER")).count() != 1)
            throw new IllegalArgumentException("流程必须有且只有一个触发节点");
        if (nodes.stream().noneMatch(node -> node.type().equals("END")))
            throw new IllegalArgumentException("流程至少需要一个终点");
        if (nodes.stream().noneMatch(node -> node.type().equals("AGENT")))
            throw new IllegalArgumentException("流程至少需要一个岗位节点");

        var edges = new ArrayList<Edge>();
        for (JsonNode value : root.path("edges")) {
            String from = value.path("from").asText("").strip();
            String to = value.path("to").asText("").strip();
            String label = value.path("label").asText("").strip();
            if (!types.containsKey(from) || !types.containsKey(to))
                throw new IllegalArgumentException("连线引用了不存在的节点：" + from + " → " + to);
            if (from.equals(to)) throw new IllegalArgumentException("节点 " + from + " 不能连向自身");
            if (types.get(from).equals("END")) throw new IllegalArgumentException("终点 " + from + " 不能有出边");
            if (types.get(to).equals("TRIGGER")) throw new IllegalArgumentException("触发节点不能有入边");
            if (label.length() > MAX_LABEL || CONTROL.matcher(label).find())
                throw new IllegalArgumentException("分支标签不能超过 " + MAX_LABEL + " 个字符：" + label);
            edges.add(new Edge(from, to, label));
        }
        var definition = new FlowDefinition(List.copyOf(nodes), List.copyOf(edges));
        for (Node node : nodes) {
            var outgoing = definition.outgoing(node.key());
            if (node.type().equals("END")) continue;
            if (outgoing.isEmpty()) throw new IllegalArgumentException("节点「" + node.name() + "」没有下一步");
            if (node.type().equals("TRIGGER") && outgoing.size() != 1)
                throw new IllegalArgumentException("触发节点只能有一条出边");
            if (outgoing.size() > 1) {
                var labels = new HashSet<String>();
                for (Edge edge : outgoing)
                    if (plain(edge.label()).isEmpty() || !labels.add(plain(edge.label())))
                        throw new IllegalArgumentException("节点「" + node.name() + "」有多个分支时，每个分支都需要不同的标签");
            }
        }
        var forward = new HashMap<String, List<String>>();
        var backward = new HashMap<String, List<String>>();
        for (Edge edge : edges) {
            forward.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to());
            backward.computeIfAbsent(edge.to(), key -> new ArrayList<>()).add(edge.from());
        }
        var reachable = reach(List.of(definition.trigger().key()), forward);
        var finishing = reach(
                nodes.stream().filter(node -> node.type().equals("END")).map(Node::key).toList(), backward);
        for (Node node : nodes) {
            if (!reachable.contains(node.key())) throw new IllegalArgumentException("节点「" + node.name() + "」无法从触发节点到达");
            if (!finishing.contains(node.key())) throw new IllegalArgumentException("节点「" + node.name() + "」无法走到任何终点");
        }
        return definition;
    }

    private static Set<String> reach(List<String> start, Map<String, List<String>> next) {
        var seen = new HashSet<>(start);
        var queue = new ArrayDeque<>(start);
        while (!queue.isEmpty())
            for (String key : next.getOrDefault(queue.poll(), List.of())) if (seen.add(key)) queue.add(key);
        return seen;
    }
}
