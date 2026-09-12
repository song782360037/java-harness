package dev.javaharness.server;

import dev.javaharness.adapters.*;
import dev.javaharness.spi.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class CommandServiceTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    JdbcSessionStore sessions;
    CommandService commands;
    Run run;
    ExecutorService workers;
    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        sessions = new JdbcSessionStore(jdbc, tx); commands = new CommandService(jdbc, tx, sessions);
        var session = sessions.createSession("command", directory.toRealPath().toString());
        run = sessions.submit(session.id(), UUID.randomUUID().toString(), "test", "NORMAL", new ModelInfo("test", "test", true)).run();
        run = sessions.transition(run.id(), RunStatus.RUNNING, "");
        workers = Executors.newVirtualThreadPerTaskExecutor();
    }
    @AfterEach void close() throws Exception { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    String shell(String windows, String posix) { return LocalCommandRunner.WINDOWS ? windows : posix; }
    CommandExecution propose(String text, int seconds) { return commands.propose(run.id(), UUID.randomUUID().toString(), run.workspace(), text, ".", "验证命令", seconds); }
    CommandExecution saved(CommandExecution value) { return commands.list(run.id()).stream().filter(c -> c.id().equals(value.id())).findFirst().orElseThrow(); }
    Future<WorkspaceTools.Result> execute(CommandExecution value) { return workers.submit(() -> commands.awaitAndExecute(value, () -> { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); })); }
    void untilStatus(CommandExecution value, String status) { await().atMost(Duration.ofSeconds(10)).until(() -> saved(value).status().equals(status)); }

    @Test void approvalIsRequiredAndRetriesNeverExecuteTwice() throws Exception {
        var value = propose(shell("Add-Content -LiteralPath marker.txt -Value once; Write-Output '你好'", "echo once >> marker.txt; echo '你好'"), 15);
        var future = execute(value);
        assertThat(saved(value).status()).isEqualTo("PENDING"); assertThat(future.isDone()).isFalse();
        assertThat(directory.resolve("marker.txt")).doesNotExist();
        commands.approve(value.id()); commands.approve(value.id());
        assertThat(future.get(20, TimeUnit.SECONDS).success()).isTrue();
        assertThat(saved(value).stdout()).contains("你好"); assertThat(saved(value).exitCode()).isZero();
        commands.approve(value.id());
        assertThat(commands.awaitAndExecute(value, () -> {}).success()).isTrue();
        assertThat(Files.readAllLines(directory.resolve("marker.txt"))).containsExactly("once");
        assertThat(sessions.events(run.sessionId(), 0, 128)).anyMatch(e -> e.type().equals("COMMAND"));
    }
    @Test void rejectedCommandNeverWritesAndRejectionIsIdempotent() throws Exception {
        var value = propose(shell("Set-Content marker.txt bad", "echo bad > marker.txt"), 10);
        var future = execute(value);
        commands.reject(value.id()); commands.reject(value.id());
        assertThat(future.get(5, TimeUnit.SECONDS).output()).contains("REJECTED");
        assertThat(directory.resolve("marker.txt")).doesNotExist();
        assertThatThrownBy(() -> commands.approve(value.id())).isInstanceOf(HarnessException.class);
    }
    @Test void stdoutStderrAndExitCodeArePreserved() throws Exception {
        var value = propose(shell("[Console]::Out.WriteLine('out'); [Console]::Error.WriteLine('err'); exit 7", "echo out; echo err >&2; exit 7"), 10);
        commands.approve(value.id()); var result = execute(value).get(15, TimeUnit.SECONDS);
        assertThat(result.success()).isFalse(); assertThat(saved(value).status()).isEqualTo("FAILED");
        assertThat(saved(value).stdout()).contains("out"); assertThat(saved(value).stderr()).contains("err"); assertThat(saved(value).exitCode()).isEqualTo(7);
    }
    @Test void powershellErrorsArePlainTextWithoutClixml() throws Exception {
        var value = propose(shell("Write-Progress -Activity 'loading' -PercentComplete 20; Write-Error 'plain-error'", "echo plain-error >&2; exit 1"), 10);
        commands.approve(value.id()); assertThat(execute(value).get(15, TimeUnit.SECONDS).success()).isFalse();
        assertThat(saved(value).stderr()).contains("plain-error").doesNotContain("CLIXML", "<Objs", "<Obj");
    }
    @Test void approvedQuotesSpacesUnicodeAndLiteralMetacharactersSurviveShellTransport() throws Exception {
        var value = propose(shell("Write-Output \"中文 with spaces\"; Write-Output 'literal \"quotes\" $() `x'; [Console]::WriteLine($PWD.Path)",
                "printf '%s\\n' \"中文 with spaces\" 'literal \"quotes\" $() `x'; pwd"), 10);
        commands.approve(value.id()); assertThat(execute(value).get(15, TimeUnit.SECONDS).success()).isTrue();
        assertThat(saved(value).stdout().replace("\r\n", "\n")).isEqualTo("中文 with spaces\nliteral \"quotes\" $() `x\n" + directory.toRealPath() + "\n");
        assertThat(saved(value).stderr()).isEmpty();
    }
    @Test void outputIsVisibleBeforeExitAndStopPreventsLaterWork() throws Exception {
        var value = propose(shell("Write-Output started; Start-Sleep -Seconds 30; Set-Content marker.txt bad", "echo started; sleep 30; echo bad > marker.txt"), 40);
        commands.approve(value.id()); var future = execute(value);
        await().atMost(Duration.ofSeconds(10)).until(() -> saved(value).stdout().contains("started"));
        assertThat(future.isDone()).isFalse();
        commands.stop(value.id()); assertThat(future.get(10, TimeUnit.SECONDS).output()).contains("CANCELLED");
        assertThat(directory.resolve("marker.txt")).doesNotExist();
    }
    @Test void commandDeadlineTerminatesProcess() throws Exception {
        var value = propose(shell("Start-Sleep -Seconds 30; Set-Content marker.txt bad", "sleep 30; echo bad > marker.txt"), 1);
        commands.approve(value.id());
        assertThat(execute(value).get(10, TimeUnit.SECONDS).output()).contains("TIMED_OUT");
        assertThat(directory.resolve("marker.txt")).doesNotExist();
    }
    @Test void excessiveOutputIsBoundedAndNotReportedAsSuccess() throws Exception {
        var value = propose(shell("[Console]::Write(('x' * 50000))", "head -c 50000 /dev/zero | tr '\\0' x"), 10);
        commands.approve(value.id()); assertThat(execute(value).get(15, TimeUnit.SECONDS).success()).isFalse();
        assertThat(saved(value).status()).isEqualTo("OUTPUT_LIMIT");
        assertThat(saved(value).stdout().length() + saved(value).stderr().length()).isEqualTo(LocalCommandRunner.MAX_OUTPUT);
    }
    @Test void cancellationDuringApprovalAndDuringExecutionSettlesWithoutReplay() throws Exception {
        var waiting = propose("echo never", 10);
        var cancel = new AtomicBoolean();
        var wait = workers.submit(() -> commands.awaitAndExecute(waiting, () -> { if (cancel.get()) throw new InterruptedException(); }));
        cancel.set(true);
        assertThatThrownBy(() -> wait.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        assertThat(saved(waiting).status()).isEqualTo("CANCELLED");
        cancel.set(false);
        var running = propose(shell("Write-Output started; Start-Sleep -Seconds 30", "echo started; sleep 30"), 40);
        commands.approve(running.id());
        var active = workers.submit(() -> commands.awaitAndExecute(running, () -> { if (cancel.get()) throw new InterruptedException(); }));
        await().atMost(Duration.ofSeconds(10)).until(() -> saved(running).stdout().contains("started"));
        cancel.set(true);
        assertThatThrownBy(() -> active.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        assertThat(saved(running).status()).isEqualTo("CANCELLED"); assertThat(saved(running).stdout()).contains("started");
    }
    @Test void restartNeverReplaysPendingOrPreviouslyStartedCommands() throws Exception {
        var pending = propose("echo pending", 10); var approved = propose("echo approved", 10); var running = propose("echo running", 10);
        commands.approve(approved.id());
        jdbc.update("UPDATE harness_command SET status='RUNNING',stdout='saved output' WHERE id=?", running.id());
        commands = new CommandService(jdbc, tx, sessions);
        assertThat(commands.list(run.id())).extracting(CommandExecution::status).containsOnly("INTERRUPTED");
        assertThat(saved(running).stdout()).isEqualTo("saved output");
        assertThatThrownBy(() -> commands.approve(pending.id())).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> commands.approve(approved.id())).isInstanceOf(HarnessException.class);
    }
    @Test void validationAndStoppedRunCannotApprove() {
        assertThatThrownBy(() -> commands.propose(run.id(), "bad", run.workspace(), "echo bad", "../", "test", 10)).isInstanceOf(HarnessException.class);
        assertThatThrownBy(() -> propose("echo bad", 0)).isInstanceOf(HarnessException.class);
        var value = propose("echo never", 10);
        sessions.transition(run.id(), RunStatus.CANCELLING, "stop");
        assertThatThrownBy(() -> commands.approve(value.id())).isInstanceOf(HarnessException.class);
    }
    @Test void outputPersistenceFailureKillsCommand() throws Exception {
        var runner = new LocalCommandRunner();
        assertThatThrownBy(() -> runner.run(shell("Write-Output started; Start-Sleep -Seconds 30; Set-Content marker.txt bad", "echo started; sleep 30; echo bad > marker.txt"),
                directory, 40, () -> {}, () -> false, (out, err) -> { if (out.contains("started")) throw new IllegalStateException("storage unavailable"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(directory.resolve("marker.txt")).doesNotExist();
    }
}
