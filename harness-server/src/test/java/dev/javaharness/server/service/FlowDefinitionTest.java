package dev.javaharness.server.service;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.server.service.FlowDefinition.Edge;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowDefinitionTest {
    private static final String NODES = """
            {"key":"start","type":"TRIGGER","name":"触发","description":"新需求"},
            {"key":"review","type":"AGENT","name":"审查员","description":"审查需求"},
            {"key":"confirm","type":"HUMAN","name":"确认","description":"确认结果"},
            {"key":"done","type":"END","name":"已完成","description":""},
            {"key":"rejected","type":"END","name":"已驳回","description":""}
            """;
    private static final String EDGES = """
            {"from":"start","to":"review","label":""},
            {"from":"review","to":"confirm","label":"通过"},
            {"from":"review","to":"rejected","label":"不通过"},
            {"from":"confirm","to":"done","label":""}
            """;

    private static String definition(String nodes, String edges) {
        return "{\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}";
    }

    @Test
    void parsesAValidDefinitionAndFillsAgentDefaults() {
        FlowDefinition definition = FlowDefinition.parse(definition(NODES, EDGES));

        assertThat(definition.trigger().key()).isEqualTo("start");
        assertThat(definition.node("review").modelProfileId()).isEqualTo("default");
        assertThat(definition.node("review").approvalMode()).isEqualTo("MANUAL");
        assertThat(definition.node("confirm").approvalMode()).isEmpty();
        assertThat(definition.outgoing("review")).extracting(Edge::label).containsExactly("通过", "不通过");
        // The stored JSON parses back to the same definition.
        assertThat(FlowDefinition.parse(definition.json())).isEqualTo(definition);
    }

    @Test
    void rejectsStructuresThatCannotRun() {
        assertThatThrownBy(() -> FlowDefinition.parse("not json")).hasMessageContaining("不是有效的 JSON");
        assertThatThrownBy(() -> FlowDefinition.parse("{\"nodes\":{}}")).hasMessageContaining("nodes 和 edges");
        // Two branches need two different labels.
        assertThatThrownBy(() -> FlowDefinition.parse(definition(NODES, EDGES.replace("不通过", "通过"))))
                .hasMessageContaining("不同的标签");
        // Labels that differ only by punctuation read as the same verdict.
        assertThatThrownBy(() -> FlowDefinition.parse(definition(NODES, EDGES.replace("不通过", "通过。"))))
                .hasMessageContaining("不同的标签");
        // A node nobody leads to.
        assertThatThrownBy(() -> FlowDefinition.parse(definition(
                        NODES + ",{\"key\":\"lost\",\"type\":\"AGENT\",\"name\":\"孤岛\",\"description\":\"x\"}",
                        EDGES + ",{\"from\":\"lost\",\"to\":\"done\",\"label\":\"\"}")))
                .hasMessageContaining("无法从触发节点到达");
        // Two agents that only lead to each other never finish.
        assertThatThrownBy(() -> FlowDefinition.parse(definition(
                        NODES
                                + ",{\"key\":\"a\",\"type\":\"AGENT\",\"name\":\"甲\",\"description\":\"x\"}"
                                + ",{\"key\":\"b\",\"type\":\"AGENT\",\"name\":\"乙\",\"description\":\"x\"}",
                        EDGES
                                + ",{\"from\":\"review\",\"to\":\"a\",\"label\":\"转交\"}"
                                + ",{\"from\":\"a\",\"to\":\"b\",\"label\":\"\"},{\"from\":\"b\",\"to\":\"a\",\"label\":\"\"}")))
                .hasMessageContaining("无法走到任何终点");
        assertThatThrownBy(() -> FlowDefinition.parse(
                        definition(NODES, EDGES + ",{\"from\":\"done\",\"to\":\"review\",\"label\":\"\"}")))
                .hasMessageContaining("不能有出边");
        assertThatThrownBy(() -> FlowDefinition.parse(
                        definition(NODES, EDGES + ",{\"from\":\"start\",\"to\":\"confirm\",\"label\":\"直达\"}")))
                .hasMessageContaining("触发节点只能有一条出边");
        assertThatThrownBy(() -> FlowDefinition.parse(
                        definition(NODES.replace("\"description\":\"审查需求\"", "\"description\":\"\""), EDGES)))
                .hasMessageContaining("缺少职责说明");
        assertThatThrownBy(() -> FlowDefinition.parse(definition(NODES.replace("AGENT", "HUMAN"), EDGES)))
                .hasMessageContaining("至少需要一个岗位节点");
    }

    @Test
    void readsTheLastVerdictLine() {
        List<Edge> outgoing = FlowDefinition.parse(definition(NODES, EDGES)).outgoing("review");

        assertThat(FlowPrompts.verdict("先看了一遍。\n结论：通过", outgoing).to()).isEqualTo("confirm");
        assertThat(FlowPrompts.verdict("结论：通过\n复查后改判。\n**结论: 「不通过」。**", outgoing).to())
                .isEqualTo("rejected");
        assertThat(FlowPrompts.verdict("结论：再想想", outgoing)).isNull();
        assertThat(FlowPrompts.verdict("我觉得可以通过", outgoing)).isNull();
        // A verdict mentioned before the last line is not a verdict.
        assertThat(FlowPrompts.verdict("补齐测试的话，结论：通过。\n目前缺测试，我无法下结论。", outgoing)).isNull();
        assertThat(FlowPrompts.verdict("综合结论：通过\n以上。", outgoing)).isNull();
    }
}
