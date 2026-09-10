package dev.javaharness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.LocalWorkspaceTools;
import dev.javaharness.spi.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class LocalWorkspaceToolsTest {
    @TempDir Path directory;
    LocalWorkspaceTools tools;
    @BeforeEach void setup() throws Exception {
        tools = new LocalWorkspaceTools(directory, Duration.ofSeconds(5));
        Files.createDirectory(directory.resolve("src"));
        Files.writeString(directory.resolve("src/App.java"), "class App {\n  // 你好 🌱\n}\n");
    }
    WorkspaceTools.Result call(String name, Map<String, Object> args) throws Exception {
        return tools.execute(new ToolCall("call", name, new ObjectMapper().writeValueAsString(args)), () -> {});
    }
    @Test void listsReadsLineRangesAndSearchesLiteralTextWithoutWriting() throws Exception {
        assertThat(call("list_directory", Map.of()).output()).contains("src/");
        assertThat(call("read_file", Map.of("path", "src/App.java", "start_line", 2, "end_line", 2)).output())
                .contains("2:   // 你好 🌱").doesNotContain("class App");
        assertThat(call("search_files", Map.of("query", "class App {")).output()).contains("src/App.java:1: class App {");
        assertThat(call("search_files", Map.of("query", "CLASS")).output()).contains("匹配 0 处");
        assertThat(Files.readString(directory.resolve("src/App.java"))).isEqualTo("class App {\n  // 你好 🌱\n}\n");
    }
    @ParameterizedTest @ValueSource(strings = {"../outside", "/etc/passwd", "C:/Windows/win.ini", "src\\App.java", "src/App.java:stream", ".env", ".env.local", ".git/config", "secret.pem", "target/compiled"})
    void rejectsEscapesAbsolutePathsAndExcludedFiles(String path) throws Exception {
        if (!path.contains(":") && !path.contains("..") && !path.startsWith("/") && !path.contains("\\")) {
            Files.createDirectories(directory.resolve(path).getParent());
            Files.writeString(directory.resolve(path), "sensitive value");
        }
        assertThat(call("read_file", Map.of("path", path)).success()).isFalse();
        assertThat(call("search_files", Map.of("query", "sensitive value")).output()).doesNotContain(": sensitive value");
    }
    @Test void skipsLinksAndRefusesTraversalThroughThem() throws Exception {
        Path outside = Files.createTempDirectory(directory.getParent(), "harness-link-test-");
        Path secret = outside.resolve("outside.txt");
        Path link = directory.resolve("linked");
        try {
            Files.writeString(secret, "outside-content");
            try { Files.createSymbolicLink(link, outside); }
            catch (UnsupportedOperationException | java.io.IOException unavailable) {
                if (!System.getProperty("os.name").startsWith("Windows")) Assumptions.abort("Symlinks unavailable");
                // Windows junctions do not require Developer Mode / symlink privileges.
                Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                Assumptions.assumeTrue(process.waitFor() == 0, "Junction creation unavailable");
            }
            assertThat(call("read_file", Map.of("path", "linked/outside.txt")).success()).isFalse();
            assertThat(call("list_directory", Map.of()).output()).doesNotContain("linked");
            assertThat(call("search_files", Map.of("query", "outside-content")).output()).doesNotContain("outside.txt");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(secret);
            Files.deleteIfExists(outside);
        }
    }
    @Test void boundsFileSizeEncodingArgumentsAndOutput() throws Exception {
        Files.write(directory.resolve("binary"), new byte[] { 0, 1, 2 });
        Files.write(directory.resolve("invalid"), new byte[] { (byte) 0xff });
        Files.writeString(directory.resolve("large"), "x".repeat(1_048_577));
        for (String file : new String[] {"binary", "invalid", "large"}) assertThat(call("read_file", Map.of("path", file)).success()).isFalse();
        assertThat(call("read_file", Map.of("path", "src/App.java", "end_line", 201)).success()).isTrue();
        assertThat(call("read_file", Map.of("path", "src/App.java", "start_line", 1.5)).success()).isFalse();
        assertThat(call("read_file", Map.of("path", "src/App.java", "unexpected", true)).success()).isFalse();
        assertThat(call("shell", Map.of()).success()).isFalse();
        assertThat(tools.execute(new ToolCall("id", "read_file", "{} {}"), () -> {}).success()).isFalse();
        Files.writeString(directory.resolve("long-line"), "🌱".repeat(9000));
        String output = call("read_file", Map.of("path", "long-line")).output();
        assertThat(output.length()).isLessThan(12_000);
        assertThat(output).contains("结果已截断");
        assertThat(output).doesNotContain("\uFFFD");
    }
    @Test void oversizedLineRangeReadsAvailableContentAndExplainsContinuation() throws Exception {
        var small = call("read_file", Map.of("path", "src/App.java", "start_line", 1, "end_line", 240));
        assertThat(small.success()).isTrue();
        assertThat(small.output()).contains("class App", "200 行上限");
        Files.writeString(directory.resolve("lines"), "content\n".repeat(250));
        var result = call("read_file", Map.of("path", "lines", "start_line", 1, "end_line", 240));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("200: content", "第 201 行继续读取").doesNotContain("201: content");
        assertThat(call("read_file", Map.of("path", "lines", "start_line", 201, "end_line", 240)).output()).contains("201: content", "240: content");
    }

    @Test void directoryDepthFindsJavaPackagesWithoutFollowingExcludedTrees() throws Exception {
        Path source = directory.resolve("module/src/main/java/dev/example/App.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, "class App {}");
        Path excluded = directory.resolve("module/target/classes/hidden");
        Files.createDirectories(excluded.getParent()); Files.writeString(excluded, "hidden");
        assertThat(call("list_directory", Map.of("depth", 8)).output()).contains("module/src/main/java/dev/example/App.java").doesNotContain("module/target");
        assertThat(call("list_directory", Map.of()).output()).contains("module/src/main/").doesNotContain("module/src/main/java/");
        assertThat(call("list_directory", Map.of("depth", 1)).output()).contains("module/").doesNotContain("module/src/");
        assertThat(call("list_directory", Map.of("depth", 9)).success()).isFalse();
        assertThat(call("list_directory", Map.of("depth", 0)).success()).isFalse();
    }

    @Test void cancellationAndDeadlinePreventReads() throws Exception {
        assertThatThrownBy(() -> tools.execute(new ToolCall("id", "read_file", "{\"path\":\"src/App.java\"}"),
                () -> { throw new InterruptedException(); })).isInstanceOf(InterruptedException.class);
        var immediate = new LocalWorkspaceTools(directory, Duration.ofNanos(1));
        assertThat(immediate.execute(new ToolCall("id", "list_directory", "{}"), () -> {}).output()).contains("时间限制");
    }
    @Test void searchAndListingReportTruncation() throws Exception {
        Files.writeString(directory.resolve("matches"), "needle\n".repeat(150));
        assertThat(call("search_files", Map.of("query", "needle")).output()).contains("结果已截断", "匹配 100 处");
        for (int i = 0; i < 201; i++) Files.writeString(directory.resolve("f" + i), "");
        assertThat(call("list_directory", Map.of()).output()).contains("结果已截断");
    }
}
