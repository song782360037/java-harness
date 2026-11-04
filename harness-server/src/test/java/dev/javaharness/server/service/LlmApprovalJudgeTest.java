package dev.javaharness.server.service;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.approval.ApprovalPolicy.Verdict;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.model.ModelUsage;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.RunConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
    void judgeSendsInstructionsAndCountsTheirTokens() throws Exception {
        var requests = new ArrayList<ModelRequest>();
        ModelProvider model = new ModelProvider() {
            public ModelInfo info() {
                return new ModelInfo("test", "judge-model", true);
            }

            public void stream(ModelRequest request, Cancellation cancellation, Consumer<String> onText) {
                requests.add(request);
                onText.accept("{\"risk\":\"low\",\"authorization\":\"high\",\"reason\":\"常规构建\"}");
            }
        };
        var configuration = new RunConfiguration(
                model.info(),
                Duration.ofSeconds(10),
                8000,
                1000,
                8,
                TokenCounter.approximate(),
                workspace -> new RunConfiguration.Resources(model, null, () -> {}));
        try (var real = new LlmApprovalJudge(() -> () -> configuration)) {
            var judgement = real.judge("default", "{\"action\":{}}", Duration.ofSeconds(5), () -> {});
            assertThat(judgement.verdict()).isEqualTo(Verdict.APPROVE);
            assertThat(requests).hasSize(1);
            var request = requests.getFirst();
            assertThat(request.instructions()).isEqualTo(LlmApprovalJudge.INSTRUCTIONS);
            assertThat(request.messages()).extracting(ModelMessage::role).containsExactly("user");
            assertThat(request.context().tokens())
                    .isGreaterThan(TokenCounter.approximate().count(LlmApprovalJudge.INSTRUCTIONS));
        }
    }

    @Test
    void rejectsMissingOrInvalidAnswers() {
        assertThatThrownBy(() -> judge.parse(turn("可以执行"), "m")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> judge.parse(turn("{\"risk\":\"safe\",\"authorization\":\"high\"}"), "m"))
                .isInstanceOf(IllegalStateException.class);
    }
}
