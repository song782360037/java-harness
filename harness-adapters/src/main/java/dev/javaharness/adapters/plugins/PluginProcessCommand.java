package dev.javaharness.adapters.plugins;

import static dev.javaharness.adapters.plugins.PluginJson.invalid;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Keep Windows batch invocation explicit and reject arguments that cmd would reinterpret. */
final class PluginProcessCommand {
    private PluginProcessCommand() {}

    static List<String> prepare(List<String> command) {
        if (!System.getProperty("os.name").startsWith("Windows")) return List.copyOf(command);
        String executable = command.getFirst().toLowerCase(Locale.ROOT);
        if (!executable.endsWith(".cmd") && !executable.endsWith(".bat")) return List.copyOf(command);
        for (String part : command) {
            if (part.indexOf('"') >= 0
                    || part.indexOf('%') >= 0
                    || part.indexOf('!') >= 0
                    || part.indexOf('\r') >= 0
                    || part.indexOf('\n') >= 0
                    || part.endsWith("\\")) throw invalid("Windows 批处理参数不支持引号、%、!、换行或末尾反斜杠；请改用 node.exe 与脚本路径等直接启动方式");
        }
        Path interpreter = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
        String script = command.stream().map(part -> "\"" + part + "\"").collect(Collectors.joining(" "));
        return List.of(interpreter.toString(), "/d", "/v:off", "/s", "/c", "\"" + script + "\"");
    }
}
