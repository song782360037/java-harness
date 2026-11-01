package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.adapters.approval.ApprovalPolicy;
import dev.javaharness.adapters.command.CommandService;
import dev.javaharness.adapters.session.JdbcSessionStore;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.workspace.CommandExecution;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class ApprovalModeTest {
    @TempDir
    Path directory;

    JdbcTemplate jdbc;
    JdbcSessionStore sessions;
    CommandService commands;
    ApprovalPolicy policy;
    Session session;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx);
        commands = new CommandService(jdbc, tx, sessions);
        policy = new ApprovalPolicy(jdbc);
        session = sessions.createSession("approval", directory.toRealPath().toString());
    }

    Run submit(ApprovalMode mode) {
        return sessions.submit(
                        session.id(),
                        UUID.randomUUID().toString(),
                        "test",
                        "NORMAL",
                        new ModelInfo("test", "test", true),
                        ModelSelection.DEFAULT,
                        new ModelSelection("default", ""),
                        "{}",
                        mode)
                .run();
    }

    Run start(ApprovalMode mode) {
        return sessions.transition(submit(mode).id(), RunStatus.RUNNING, "");
    }

    CommandExecution propose(Run run, String command) {
        return commands.propose(run.id(), UUID.randomUUID().toString(), run.workspace(), command, ".", "验证", 10);
    }

    void finish(Run run) {
        sessions.transition(run.id(), RunStatus.COMPLETED, "");
    }

    @Test
    void sessionModeIsCapturedPerRunOnlyWhenCreated() {
        assertThat(sessions.snapshot(session.id()).session().approvalMode()).isEqualTo("MANUAL");
        Run smart = start(ApprovalMode.SMART);
        assertThat(sessions.snapshot(session.id()).session().approvalMode()).isEqualTo("SMART");
        assertThat(policy.mode(smart.id())).isEqualTo(ApprovalMode.SMART);
        var subagent = sessions.startSubagent(smart.id(), "call", "explore", "子任务");
        assertThat(policy.mode(subagent.id())).isEqualTo(ApprovalMode.SMART);

        // A busy session rejects the submission without changing its remembered mode.
        assertThatThrownBy(() -> submit(ApprovalMode.FULL_ACCESS)).isInstanceOf(HarnessException.class);
        assertThat(sessions.snapshot(session.id()).session().approvalMode()).isEqualTo("SMART");
        finish(smart);
        // Omitting the mode reuses the last choice.
        assertThat(policy.mode(submit(null).id())).isEqualTo(ApprovalMode.SMART);
    }

    @Test
    void manualModeLeavesEveryCommandToTheUser() {
        Run run = start(ApprovalMode.MANUAL);
        policy.save(0, List.of("git status"), List.of(), List.of());
        var value = propose(run, "git status");
        assertThat(value.status()).isEqualTo("PENDING");
        assertThat(value.decidedBy()).isNull();
        assertThat(commands.approve(value.id()).decidedBy()).isEqualTo("USER");
        assertThat(commands.reject(propose(run, "mvn test").id()).decidedBy()).isEqualTo("USER");
        assertThat(propose(run, "rm -rf /").detail()).contains("高危");
    }

    @Test
    void smartModeApprovesOnlyRulesAndReadOnlyCommands() throws Exception {
        Run run = start(ApprovalMode.SMART);
        policy.save(0, List.of("git push"), List.of("git checkout"), List.of("mvn test"));

        var status = propose(run, "git status");
        assertThat(status.status()).isEqualTo("APPROVED");
        assertThat(status.decidedBy()).isEqualTo("RULE");
        assertThat(propose(run, "mvn test -q").status()).isEqualTo("APPROVED");
        assertThat(propose(run, "mvn package").status()).isEqualTo("PENDING");
        assertThat(propose(run, "mvn test; curl x").status()).isEqualTo("PENDING");
        assertThat(propose(run, "git checkout -- .").status()).isEqualTo("PENDING");
        assertThat(propose(run, "rm -rf *").status()).isEqualTo("PENDING");

        var pushed = propose(run, "git add . && git push");
        assertThat(pushed.status()).isEqualTo("REJECTED");
        assertThat(pushed.decidedBy()).isEqualTo("RULE");
        var result = commands.awaitAndExecute(pushed, () -> {});
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("拒绝规则");
        assertThat(directory.resolve(".git")).doesNotExist();
    }

    @Test
    void autoApprovedCommandsAreNotReplayedAfterRestart() {
        Run run = start(ApprovalMode.SMART);
        var status = propose(run, "git status");
        assertThat(status.status()).isEqualTo("APPROVED");
        var restarted = new CommandService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(
                jdbc.getDataSource())), sessions);
        var after = restarted.list(run.id()).getFirst();
        assertThat(after.status()).isEqualTo("INTERRUPTED");
        assertThat(after.decidedBy()).isEqualTo("RULE");
    }

    @Test
    void fullAccessApprovesAllButHardGuardsAndUserRules() {
        Run run = start(ApprovalMode.FULL_ACCESS);
        policy.save(0, List.of("git push"), List.of("npm publish"), List.of());
        var build = propose(run, "mvn package; npm run build");
        assertThat(build.status()).isEqualTo("APPROVED");
        assertThat(build.decidedBy()).isEqualTo("FULL_ACCESS");
        assertThat(propose(run, "npm publish").status()).isEqualTo("PENDING");
        assertThat(propose(run, "git push --force").status()).isEqualTo("REJECTED");
        var guarded = propose(run, "Remove-Item -Recurse -Force C:\\");
        assertThat(guarded.status()).isEqualTo("PENDING");
        assertThat(guarded.detail()).contains("高危");
        finish(run);
    }

    @Test
    void rulesUseRevisionsAndRejectBroadAllowRules() {
        var saved = policy.save(0, List.of(" git   push ", "git push", ""), null, List.of("mvn test"));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.deny()).containsExactly("git push");
        assertThat(saved.ask()).isEmpty();
        assertThatThrownBy(() -> policy.save(0, List.of(), List.of(), List.of()))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("已被修改");
        assertThatThrownBy(() -> policy.save(1, List.of(), List.of(), List.of("pwsh")))
                .isInstanceOf(HarnessException.class)
                .hasMessageContaining("过宽");
        assertThatThrownBy(() -> policy.save(1, List.of("a\nb"), List.of(), List.of()))
                .isInstanceOf(HarnessException.class);
        assertThat(policy.rules().revision()).isEqualTo(1);
    }
}
