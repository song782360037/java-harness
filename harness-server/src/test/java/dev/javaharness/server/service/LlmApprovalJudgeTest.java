package dev.javaharness.server.service;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.approval.ApprovalPolicy.Verdict;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.model.ModelUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmApprovalJudgeTest {
    final LlmApprovalJudge judge = new LlmApprovalJudge(() -> {
        throw new IllegalStateException("not used");
    });

    ModelTurn turn(String text) {
        return new ModelTurn(text, List.of(), new ModelUsage(12, 3, 0, 1));
    }

    @Test
    void mapsRiskAndAuthorizationConservatively() throws Exception {
        var approved = judge.parse(turn("```json\n{\"risk\":\"low\",\"authorization\":\"high\",\"reason\":\"常规构建\"}\n```"), "m");
        assertThat(approved.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(approved.reason()).contains("常规构建");
        assertThat(approved.inputTokens()).isEqualTo(12);
        assertThat(judge.parse(turn("{\"risk\":\"medium\",\"authorization\":\"low\"}"), "m").verdict())
                .isEqualTo(Verdict.ASK);
        assertThat(judge.parse(turn("{\"risk\":\"high\",\"authorization\":\"high\"}"), "m").verdict())
                .isEqualTo(Verdict.ASK);
        assertThat(judge.parse(turn("{\"risk\":\"critical\",\"authorization\":\"high\"}"), "m").verdict())
                .isEqualTo(Verdict.REJECT);
    }

    @Test
    void rejectsMissingOrInvalidAnswers() {
        assertThatThrownBy(() -> judge.parse(turn("可以执行"), "m")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> judge.parse(turn("{\"risk\":\"safe\",\"authorization\":\"high\"}"), "m"))
                .isInstanceOf(IllegalStateException.class);
    }
}
