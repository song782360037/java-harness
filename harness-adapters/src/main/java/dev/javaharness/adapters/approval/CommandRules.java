package dev.javaharness.adapters.approval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 命令文本的规则匹配：高危识别、组合结构识别、按词前缀匹配用户规则与内置只读命令。
 * 只是尽力而为的文本匹配，不是安全边界；无法确认时一律交给人工。
 * 高危与拒绝/询问规则会先去掉 sudo、cmd /c、程序路径等包装再匹配；允许规则与只读清单只接受原样的简单命令。
 */
public final class CommandRules {
    /** Any of these makes a command compound or indirect, so allow rules and the read-only list never apply. */
    private static final Pattern COMPOUND = Pattern.compile("[;|&`<>$(){}\\r\\n]|[\\p{Z}&&[^ ]]");
    /** Segment separators used to check deny and ask rules inside compound commands. */
    private static final Pattern SEGMENTS = Pattern.compile("[;|&`<>(){}\\r\\n]+|\\$\\(");
    /** PowerShell also splits on Unicode spaces such as NBSP. */
    private static final Pattern BLANK = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern DOWNLOAD_AND_RUN = Pattern.compile(
            "\\b(curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod)\\b[^\\r\\n]*\\|\\s*&?\\s*"
                    + "(sh|bash|zsh|iex|invoke-expression|pwsh|powershell|python[0-9.]*)\\b");
    /** An interpreter running a download through a subexpression, e.g. iex (iwr ...) or bash -c "$(curl ...)". */
    private static final Pattern RUN_DOWNLOADED = Pattern.compile(
            "\\b(iex|invoke-expression|sh|bash|zsh|pwsh|powershell|python[0-9.]*)\\b[^\\r\\n]*[(]\\s*"
                    + "(iwr|irm|invoke-webrequest|invoke-restmethod|curl|wget|new-object)\\b|\\bdownloadstring\\b");
    /** Programs that start another process from arguments that cannot be inspected as text. */
    private static final Set<String> LAUNCHERS = Set.of("start-process", "saps", "start");
    /** Delete targets after trailing slashes and wildcards are removed; empty means root or "everything". */
    private static final Pattern CRITICAL_TARGET = Pattern.compile(
            "|\\.|(\\.\\.[/\\\\])*\\.\\.|~|\\$home|\\$env:(userprofile|home|homepath|systemroot|windir)|%userprofile%"
                    + "|[a-z]:|filesystem::.*");
    private static final Set<String> DELETE = Set.of("rm", "rmdir", "rd", "del", "erase", "remove-item", "ri");
    private static final Set<String> SYSTEM = Set.of(
            "format",
            "format-volume",
            "diskpart",
            "clear-disk",
            "initialize-disk",
            "remove-partition",
            "bcdedit",
            "shutdown",
            "restart-computer",
            "stop-computer",
            "reboot",
            "halt",
            "poweroff");
    /** Prefixes that run the following words as a command. */
    private static final Set<String> WRAPPERS = Set.of(
            "sudo", "doas", "env", "command", "exec", "nohup", "time", "nice", "timeout", "eval", "iex",
            "invoke-expression");
    /** git global options that take the next word as their value. */
    private static final Set<String> GIT_VALUE_OPTIONS =
            Set.of("-c", "--git-dir", "--work-tree", "--namespace", "--super-prefix", "--config-env");
    /** PowerShell providers and drives; a value starting with one reads outside the workspace. */
    private static final Pattern PROVIDER = Pattern.compile(
            "([a-z]|env|variable|function|alias|hklm|hkcu|cert|wsman|registry|filesystem):.*|.*::.*");
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "cmd", "powershell", "pwsh");
    /** An interpreter followed only by options as an allow rule would approve arbitrary code. */
    private static final Set<String> INTERPRETERS = Set.of(
            "sh", "bash", "zsh", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "python", "python3", "py",
            "node", "deno", "ruby", "perl", "php", "java", "iex", "invoke-expression", "invoke-command",
            "start-process", "npx", "env", "sudo", "runas", "eval", "exec", "xargs");
    private static final Set<String> READ_ONLY_EXACT = Set.of(
            "java -version", "java --version", "mvn -v", "mvn -version", "mvn --version", "node -v",
            "node --version", "npm -v", "npm --version", "python --version", "python3 --version", "git --version",
            "gradle --version", "pwd", "get-location", "whoami");
    private static final List<List<String>> READ_ONLY_PREFIXES = List.of(
                    "git status", "git diff", "git log", "git show", "git blame", "git ls-files", "git rev-parse", "ls",
                    "dir", "get-childitem", "gci", "cat", "type", "get-content", "gc", "select-string", "sls",
                    "findstr", "grep", "test-path", "resolve-path", "get-item", "get-command", "where.exe", "which",
                    "head", "tail", "wc")
            .stream()
            .map(CommandRules::tokens)
            .toList();

    private CommandRules() {}

    /** Reason a command is too dangerous to approve automatically, or null. */
    public static String danger(String command) {
        // PowerShell's backtick escape can split a program name, so also check the text without it;
        // ${NAME} becomes $NAME so braces do not split the delete target.
        String braced = command.replaceAll("\\$\\{([^}]*)}", "\\$$1");
        for (String text : List.of(braced, braced.replace("`", ""))) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (DOWNLOAD_AND_RUN.matcher(lower).find() || RUN_DOWNLOADED.matcher(lower).find())
                return "下载并直接执行远程脚本";
            if (encoded(text)) return "编码命令，无法检查实际内容";
            for (List<String> segment : normalizedSegments(text)) {
                String program = segment.getFirst();
                if (LAUNCHERS.contains(program)) return "间接启动进程，无法检查实际参数";
                if (SYSTEM.contains(program) || program.startsWith("mkfs")) return "磁盘或系统级操作";
                if (program.equals("dd") && segment.stream().anyMatch(token -> token.startsWith("of=/dev/")))
                    return "直接写入磁盘设备";
                // Wrapper options with values (sudo -u root, timeout 5) can hide the delete program, so scan every word.
                for (int i = 0; i < segment.size(); i++)
                    if (DELETE.contains(program(segment.get(i)))
                            && segment.stream()
                                    .skip(i + 1)
                                    .map(CommandRules::target)
                                    .anyMatch(target -> target != null && critical(target)))
                        return "删除根目录、用户目录或整个当前目录";
            }
        }
        return null;
    }

    public static boolean compound(String command) {
        return COMPOUND.matcher(command).find();
    }

    /** First rule matching the whole command or any unwrapped segment; used for deny and ask. */
    public static String matchAny(List<String> rules, String command) {
        var segments = normalizedSegments(command);
        segments.add(tokens(command));
        for (String rule : rules) {
            var prefix = tokens(rule);
            if (segments.stream().anyMatch(segment -> startsWith(segment, prefix))) return rule;
        }
        return null;
    }

    /** First rule matching a simple command as written; compound commands never match allow rules. */
    public static String matchSimple(List<String> rules, String command) {
        if (compound(command)) return null;
        var tokens = tokens(command);
        for (String rule : rules) if (startsWith(tokens, tokens(rule))) return rule;
        return null;
    }

    /** Built-in read-only commands whose arguments stay inside the working directory. */
    public static boolean readOnly(String command) {
        if (compound(command)) return false;
        var tokens = tokens(command);
        if (READ_ONLY_EXACT.contains(String.join(" ", tokens))) return true;
        for (var prefix : READ_ONLY_PREFIXES)
            if (startsWith(tokens, prefix)
                    && tokens.stream().skip(prefix.size()).noneMatch(CommandRules::escapes)) return true;
        return false;
    }

    /** Validation message for an allow rule, or null when acceptable. */
    public static String invalidAllow(String rule) {
        if (compound(rule)) return "允许规则不能包含组合、重定向或变量符号：" + rule;
        var tokens = tokens(rule);
        if (!tokens.isEmpty()
                && INTERPRETERS.contains(tokens.getFirst())
                && tokens.stream().skip(1).allMatch(CommandRules::option))
            return "允许规则过宽，会放行任意代码：" + rule;
        return null;
    }

    static List<String> tokens(String text) {
        String trimmed = BLANK.matcher(text).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split(" "));
    }

    private static List<List<String>> normalizedSegments(String command) {
        return SEGMENTS.splitAsStream(command)
                .map(CommandRules::tokens)
                .map(CommandRules::unwrap)
                .filter(tokens -> !tokens.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** Strip quotes, wrapper commands, shell -c / cmd /c, program paths and git global options. */
    private static List<String> unwrap(List<String> tokens) {
        var list = new ArrayList<String>();
        for (String token : tokens) {
            String value = token.replace("\"", "").replace("'", "");
            if (!value.isEmpty()) list.add(value);
        }
        while (!list.isEmpty()) {
            String program = program(list.getFirst());
            list.set(0, program);
            if (WRAPPERS.contains(program)) {
                list.removeFirst();
                while (!list.isEmpty() && (option(list.getFirst()) || list.getFirst().contains("=")))
                    list.removeFirst();
            } else if (SHELLS.contains(program) && list.size() > 1 && option(list.get(1))) {
                list.removeFirst();
                while (!list.isEmpty() && option(list.getFirst())) list.removeFirst();
            } else break;
        }
        if (!list.isEmpty() && list.getFirst().equals("git"))
            for (int i = 1; i < list.size() && list.get(i).startsWith("-"); )
                list.subList(i, Math.min(list.size(), GIT_VALUE_OPTIONS.contains(list.get(i)) ? i + 2 : i + 1))
                        .clear();
        return list;
    }

    /** PowerShell accepts -ec or any prefix of -EncodedCommand (not -ExecutionPolicy). */
    private static boolean encoded(String command) {
        return SEGMENTS.splitAsStream(command).map(CommandRules::tokens).anyMatch(tokens -> {
            int shell = -1;
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i).replace("\"", "").replace("'", "");
                if (Set.of("powershell", "pwsh").contains(program(token))) shell = i;
                else if (shell >= 0
                        && (token.equals("-ec") || token.length() > 1 && "-encodedcommand".startsWith(token)))
                    return true;
            }
            return false;
        });
    }

    private static boolean startsWith(List<String> tokens, List<String> prefix) {
        return !prefix.isEmpty()
                && tokens.size() >= prefix.size()
                && tokens.subList(0, prefix.size()).equals(prefix);
    }

    private static String program(String token) {
        String name = token.substring(Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\')) + 1);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static boolean option(String token) {
        return token.startsWith("-") || token.matches("/[a-z?]+");
    }

    /** Path argument of a delete command, including PowerShell's -Path:value form; null for plain options. */
    private static String target(String token) {
        if (token.startsWith("-")) {
            int colon = token.indexOf(':');
            return colon < 0 ? null : token.substring(colon + 1);
        }
        return token.matches("/[a-z]") ? null : token;
    }

    private static boolean critical(String token) {
        String target = token.replace("{", "").replace("}", "").replaceAll("[/\\\\*]+$", "");
        return CRITICAL_TARGET.matcher(target).matches();
    }

    /**
     * Absolute, home, drive, provider (env:, hkcu:, FileSystem::) or parent paths, PowerShell -Param:value, comma
     * lists with such a part, globs that may expand to "..", and options with attached paths may leave the
     * workspace; git --output writes a file.
     */
    private static boolean escapes(String token) {
        String value = token.substring(token.lastIndexOf('=') + 1).replace("\"", "").replace("'", "");
        if (token.startsWith("--output") || value.contains("[")) return true;
        if (value.contains(",")) return Arrays.stream(value.split(",")).anyMatch(CommandRules::escapes);
        if (token.startsWith("-") && !token.contains("=") && value.matches("-+[^/\\\\~:]*[/\\\\~:].*")) return true;
        if (PROVIDER.matcher(value).matches()) return true;
        for (String path : List.of(value, value.replace('\\', '/'), value.replace("\\", "")))
            if (path.startsWith("/") || path.startsWith("~") || path.matches("(.*/)?\\.\\.(/.*)?")) return true;
        return false;
    }
}
