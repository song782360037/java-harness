package dev.javaharness.server;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.workspace.GitWorkspaceTools;
import dev.javaharness.adapters.workspace.LocalWorkspaceTools;
import dev.javaharness.spi.tool.ToolCall;
import dev.javaharness.spi.tool.ToolDefinition;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Git tools must stay read-only and must not surface excluded paths. Every case runs against a
 * real repository created under the temporary directory; the suite aborts when git is unavailable.
 */
class GitWorkspaceToolsTest {
    @TempDir
    Path directory;

    GitWorkspaceTools tools;

    /** Runs git for test setup only; the assertions never go through this helper. */
    static int git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("git"));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @BeforeEach
    void setup() throws Exception {
        try {
            abortUnlessGitAvailable();
            assertThat(git(directory, "init", "-q", "-b", "main", ".")).isZero();
            assertThat(git(directory, "config", "user.name", "tester")).isZero();
            assertThat(git(directory, "config", "user.email", "tester@example.com"))
                    .isZero();
            assertThat(git(directory, "config", "core.autocrlf", "false")).isZero();
            Files.writeString(directory.resolve("tracked.txt"), "one\ntwo\n");
            Files.createDirectory(directory.resolve("pkg"));
            Files.writeString(directory.resolve("pkg/App.java"), "class App {}\n");
            assertThat(git(directory, "add", "-A")).isZero();
            assertThat(git(directory, "commit", "-qm", "init")).isZero();
            tools = new GitWorkspaceTools(
                    new LocalWorkspaceTools(directory, Duration.ofSeconds(15)), Duration.ofSeconds(15));
        } catch (IOException unavailable) {
            abort("git unavailable: " + unavailable.getMessage());
        }
    }

    private static void abortUnlessGitAvailable() throws Exception {
        try {
            Process process = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) abort("git unavailable");
        } catch (IOException unavailable) {
            abort("git unavailable: " + unavailable.getMessage());
        }
    }

    WorkspaceTools.Result call(String name, Map<String, Object> args) throws Exception {
        return tools.execute(new ToolCall("call", name, new ObjectMapper().writeValueAsString(args)), () -> {});
    }

    @Test
    void exposesReadOnlyGitToolsOnlyInsideARepositoryRoot() throws Exception {
        assertThat(tools.definitions())
                .extracting(ToolDefinition::name)
                .contains("git_status", "git_diff", "read_file", "list_directory", "search_files");
        Path outside = Files.createTempDirectory(directory.getParent(), "harness-nonrepo-");
        var plain =
                new GitWorkspaceTools(new LocalWorkspaceTools(outside, Duration.ofSeconds(5)), Duration.ofSeconds(5));
        try {
            assertThat(plain.definitions()).extracting(ToolDefinition::name).doesNotContain("git_status", "git_diff");
            assertThat(plain.execute(new ToolCall("c", "git_status", "{}"), () -> {})
                            .success())
                    .isFalse();
            // The file tools behind the wrapper keep working without git.
            assertThat(plain.definitions()).extracting(ToolDefinition::name).contains("read_file");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void reportsBranchAndPendingChangesWithoutWritingTheRepository() throws Exception {
        Files.writeString(directory.resolve("tracked.txt"), "one\nchanged\n");
        Files.writeString(directory.resolve("untracked.txt"), "new\n");
        String before = run("status", "--porcelain=v1", "--branch");
        WorkspaceTools.Result result = call("git_status", Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("main", "tracked.txt", "untracked.txt");
        assertThat(result.output()).contains("共 2 处未提交改动");
        assertThat(run("status", "--porcelain=v1", "--branch")).isEqualTo(before);
    }

    @Test
    void reportsRenameTargetAndOriginInGitOrder() throws Exception {
        assertThat(git(directory, "mv", "tracked.txt", "renamed.txt")).isZero();
        WorkspaceTools.Result result = call("git_status", Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("renamed.txt <- tracked.txt");
    }

    @Test
    void hidesExcludedPathsFromStatusAndDiff() throws Exception {
        Files.writeString(directory.resolve(".env"), "TOKEN=secret-value\n");
        Files.writeString(directory.resolve("server.pem"), "private-key-body\n");
        Files.writeString(directory.resolve("pkg/.env.local"), "OTHER=hidden\n");
        assertThat(git(directory, "add", "-f", ".env", "server.pem", "pkg/.env.local"))
                .isZero();
        assertThat(git(directory, "commit", "-qm", "secrets")).isZero();
        Files.writeString(directory.resolve(".env"), "TOKEN=rotated-value\n");
        Files.writeString(directory.resolve("server.pem"), "rotated-key-body\n");
        Files.writeString(directory.resolve("pkg/.env.local"), "OTHER=rotated-hidden\n");
        WorkspaceTools.Result status = call("git_status", Map.of());
        assertThat(status.success()).isTrue();
        assertThat(status.output()).doesNotContain(".env", "server.pem", "secret-value", "rotated");
        assertThat(status.output()).contains("被排除或不可读路径未显示");
        for (String path : List.of(".env", "server.pem", "pkg/.env.local")) {
            WorkspaceTools.Result diff = call("git_diff", Map.of("path", path));
            assertThat(diff.success()).as(path).isFalse();
            assertThat(diff.output()).as(path).doesNotContain("secret-value", "private-key-body", "rotated");
        }
    }

    @Test
    void showsPatchForOneFileAndDistinguishesStagedFromWorktree() throws Exception {
        Files.writeString(directory.resolve("pkg/App.java"), "class App { int value = 2; }\n");
        WorkspaceTools.Result unstaged = call("git_diff", Map.of("path", "pkg/App.java"));
        assertThat(unstaged.success()).isTrue();
        assertThat(unstaged.output()).contains("pkg/App.java", "+class App { int value = 2; }", "-class App {}");
        assertThat(git(directory, "add", "pkg/App.java")).isZero();
        Files.writeString(directory.resolve("pkg/App.java"), "class App { int value = 3; }\n");
        assertThat(call("git_diff", Map.of("path", "pkg/App.java")).output()).contains("value = 3");
        assertThat(call("git_diff", Map.of("path", "pkg/App.java", "staged", true))
                        .output())
                .contains("value = 2");
    }

    @Test
    void reportsNoDifferenceForUntrackedAndCleanFiles() throws Exception {
        Files.writeString(directory.resolve("fresh.txt"), "content\n");
        WorkspaceTools.Result untracked = call("git_diff", Map.of("path", "fresh.txt"));
        assertThat(untracked.success()).isTrue();
        assertThat(untracked.output()).contains("没有未暂存差异", "read_file");
        assertThat(call("git_diff", Map.of("path", "tracked.txt")).success()).isTrue();
    }

    @Test
    void rejectsDirectoriesGlobsEscapesAndMissingArguments() throws Exception {
        Files.writeString(directory.resolve("pkg/App.java"), "class App { int value = 2; }\n");
        // A directory pathspec would expand to every file inside it, including excluded ones.
        assertThat(call("git_diff", Map.of("path", "pkg")).success()).isFalse();
        assertThat(call("git_diff", Map.of("path", ".")).success()).isFalse();
        for (String path :
                List.of("../outside.txt", "/etc/passwd", "C:/Windows/win.ini", "pkg\\App.java", "*.java", "pkg/*"))
            assertThat(call("git_diff", Map.of("path", path)).success())
                    .as(path)
                    .isFalse();
        assertThat(call("git_diff", Map.of()).success()).isFalse();
        assertThat(call("git_diff", Map.of("path", "tracked.txt", "staged", "yes"))
                        .success())
                .isFalse();
        assertThat(call("git_diff", Map.of("path", "tracked.txt", "unexpected", true))
                        .success())
                .isFalse();
        assertThat(call("git_status", Map.of("path", "x")).success()).isFalse();
    }

    @Test
    void keepsChineseAndSpacedPathsIntact() throws Exception {
        Path file = directory.resolve("pkg/中文 文件.txt");
        Files.writeString(file, "content\n");
        assertThat(git(directory, "add", "-A")).isZero();
        assertThat(git(directory, "commit", "-qm", "chinese")).isZero();
        Files.writeString(file, "modified\n");
        WorkspaceTools.Result status = call("git_status", Map.of());
        assertThat(status.output()).contains("pkg/中文 文件.txt");
        WorkspaceTools.Result diff = call("git_diff", Map.of("path", "pkg/中文 文件.txt"));
        assertThat(diff.success()).isTrue();
        assertThat(diff.output()).contains("中文 文件.txt", "+modified");
    }

    @Test
    void boundsLargePatches() throws Exception {
        Files.writeString(directory.resolve("big.txt"), "old\n".repeat(1200));
        assertThat(git(directory, "add", "big.txt")).isZero();
        assertThat(git(directory, "commit", "-qm", "big")).isZero();
        Files.writeString(directory.resolve("big.txt"), "new\n".repeat(1200));
        WorkspaceTools.Result result = call("git_diff", Map.of("path", "big.txt"));
        assertThat(result.success()).isTrue();
        assertThat(result.output().length()).isLessThan(13_000);
        assertThat(result.output()).contains("已截断");
    }

    @Test
    void boundsStatusListingButKeepsTrueTotals() throws Exception {
        int total = 900;
        for (int i = 0; i < total; i++)
            Files.writeString(directory.resolve("untracked-" + i + "-" + "n".repeat(40) + ".txt"), "x\n");
        WorkspaceTools.Result result = call("git_status", Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.output().length()).isLessThan(12_500);
        // The count must describe the repository, not the truncated rendering.
        assertThat(result.output()).contains("共 " + total + " 处未提交改动", "仅显示前");
        assertThat(result.output()).doesNotContain(".txt\n[结果已截断");
    }

    @Test
    void cancellationAndDeadlineStopTheGitProcess() throws Exception {
        assertThatThrownBy(() -> tools.execute(new ToolCall("id", "git_status", "{}"), () -> {
                    throw new InterruptedException();
                }))
                .isInstanceOf(InterruptedException.class);
        var immediate =
                new GitWorkspaceTools(new LocalWorkspaceTools(directory, Duration.ofNanos(1)), Duration.ofNanos(1));
        assertThat(immediate
                        .execute(new ToolCall("id", "git_status", "{}"), () -> {})
                        .output())
                .contains("时间限制");
    }

    @Test
    void delegatesEveryNonGitCallToTheWrappedTools() throws Exception {
        assertThat(call("read_file", Map.of("path", "tracked.txt")).output()).contains("1: one");
        assertThat(call("list_directory", Map.of()).output()).contains("pkg/");
        assertThat(call("search_files", Map.of("query", "class App")).output()).contains("pkg/App.java:1");
        assertThat(tools.workspace()).isEqualTo(directory.toRealPath().toString());
    }

    /** Reads raw git output for setup verification only. */
    private String run(String... arguments) throws Exception {
        var command = new ArrayList<String>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command).directory(directory.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(30, TimeUnit.SECONDS);
        return output;
    }
}
