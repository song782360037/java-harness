package dev.javaharness.adapters.plugins;

import static org.assertj.core.api.Assertions.*;

import dev.javaharness.spi.HarnessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class PluginProcessCommandTest {
    @TempDir
    Path directory;

    /** Cmd metacharacters remain literal arguments; neither spaces nor ampersands create another command. */
    @Test
    void batchLauncherPreservesArgumentsAndDoesNotExecuteShellOperators() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("space & folder"));
        Path source = folder.resolve("Echo.java");
        Files.writeString(
                source,
                "class Echo { public static void main(String[] args) { for (String arg : args) System.out.println(\"ARG:\" + arg); } }");
        Path script = folder.resolve("echo.cmd");
        Files.writeString(
                script,
                "@\"" + Path.of(System.getProperty("java.home"), "bin", "java.exe") + "\" --source 21 \"" + source
                        + "\" %*\r\n");
        List<String> args = List.of(
                "hello world",
                "x&echo INJECTED",
                "x|echo INJECTED",
                "<input>",
                "(value)",
                "",
                "trailing space ",
                "x^y");
        var command = new ArrayList<String>();
        command.add(script.toString());
        command.addAll(args);
        String output = run(PluginProcessCommand.prepare(command));
        assertThat(output.lines().toList())
                .isEqualTo(args.stream().map(value -> "ARG:" + value).toList());
    }

    /** Batch expansion and embedded quotes must fail before a process can reinterpret the user argument. */
    @Test
    void unsafeBatchArgumentsAreRejectedWhileNativeArgvIsUnchanged() {
        for (String argument : List.of("%PATH%", "!PATH!", "a\" & echo injected", "a\nb", "C:\\folder\\")) {
            assertThatThrownBy(() -> PluginProcessCommand.prepare(List.of("C:\\fixture.cmd", argument)))
                    .isInstanceOf(HarnessException.class);
            assertThat(PluginProcessCommand.prepare(List.of("C:\\node.exe", argument)))
                    .containsExactly("C:\\node.exe", argument);
        }
    }

    /** Use the Node installation supplied by local development and the CI setup-node step, with no download. */
    @Test
    void installedNpxCmdActuallyStarts() throws Exception {
        Path npx = Arrays.stream(System.getenv("PATH").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .map(path -> path.resolve("npx.cmd"))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(npx != null, "Node/npm is not installed on this Windows host");
        assertThat(run(PluginProcessCommand.prepare(List.of(npx.toString(), "--version")))
                        .strip())
                .matches("[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    private String run(List<String> command) throws Exception {
        Path output = directory.resolve(UUID.randomUUID() + ".log");
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            String text = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(text).isZero();
            return text;
        } finally {
            process.destroyForcibly();
        }
    }
}
