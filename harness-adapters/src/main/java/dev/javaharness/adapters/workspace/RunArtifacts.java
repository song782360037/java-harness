package dev.javaharness.adapters.workspace;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.FileChange;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 运行产物：汇总已写入工作区的文件，读取其当前内容，并调用系统程序打开或定位。
 * 只按已记录的文件修改定位文件，从不接受调用方给出的任意路径。
 */
public final class RunArtifacts {
    /** 用默认程序打开时不会执行代码的扩展名；脚本、网页、可执行文件等只能在文件夹中定位。 */
    private static final Set<String> OPENABLE = Set.of(
            "md", "markdown", "txt", "log", "csv", "json", "yaml", "yml", "xml", "toml", "css", "scss", "less",
            "java", "kt", "ts", "tsx", "vue", "sql", "properties", "go", "rs", "c", "h", "cpp", "hpp", "cs",
            "proto", "graphql");

    /** 产物状态 state 为 FILE、MISSING（文件已不存在）或 UNAVAILABLE（工作区或路径无法访问）。 */
    public record Artifact(
            String changeId,
            String path,
            String operation,
            String state,
            long size,
            Instant modifiedAt,
            boolean openable) {}

    public record Content(String path, String content) {}

    /** 启动系统程序；测试中替换为记录命令。 */
    @FunctionalInterface
    public interface Launcher {
        void launch(List<String> command) throws IOException;
    }

    private final FileChangeService changes;
    private final SessionStore sessions;
    private final Launcher launcher;
    private final String os;

    public RunArtifacts(FileChangeService changes, SessionStore sessions) {
        this(changes, sessions, RunArtifacts::start, System.getProperty("os.name", ""));
    }

    public RunArtifacts(FileChangeService changes, SessionStore sessions, Launcher launcher, String os) {
        this.changes = changes;
        this.sessions = sessions;
        this.launcher = launcher;
        this.os = os;
    }

    /** 列出运行中已写入的文件，同一路径多次修改只保留最后一次。 */
    public List<Artifact> list(String runId) {
        Run run = sessions.run(runId);
        var latest = new LinkedHashMap<String, FileChange>();
        var created = new HashSet<String>();
        for (FileChange change : changes.list(runId)) {
            if (!change.status().equals("APPLIED")) continue;
            latest.put(change.path(), change);
            if (change.operation().equals("create")) created.add(change.path());
        }
        return latest.values().stream()
                .map(change -> describe(run, change, created.contains(change.path())))
                .toList();
    }

    /** 读取产物的当前内容，沿用文件修改的 1 MiB UTF-8 文本限制。 */
    public Content content(String changeId) {
        FileChange change = applied(changeId);
        try {
            WorkspaceRoots.Target location = WorkspaceRoots.target(sessions.run(change.runId()).workspace(), change.path());
            var files = new WorkspaceFileEditor(location.root());
            return new Content(change.path(), files.read(files.resolve(location.relative())));
        } catch (IOException | IllegalArgumentException error) {
            throw new HarnessException("CONFLICT", "文件无法读取：可能已被删除、超过 1 MiB 或不再是 UTF-8 文本");
        }
    }

    /** action 为 open（用默认程序打开）或 reveal（在文件管理器中定位）。 */
    public void open(String changeId, String action) {
        if (!"open".equals(action) && !"reveal".equals(action))
            throw new HarnessException("INVALID", "只支持 open 或 reveal");
        FileChange change = applied(changeId);
        if (action.equals("open") && !openable(change.path()))
            throw new HarnessException("INVALID", "该类型文件可能被系统直接执行，只能打开所在文件夹");
        Path path;
        try {
            path = locate(sessions.run(change.runId()), change);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file");
        } catch (IOException | IllegalArgumentException error) {
            throw new HarnessException("CONFLICT", "文件已不存在或无法访问");
        }
        if (action.equals("open") && !plainArgument(path.toString()))
            throw new HarnessException("INVALID", "路径含逗号或分号，只能打开所在文件夹");
        try {
            launcher.launch(command(os, action, path));
        } catch (IOException error) {
            throw new HarnessException("UNAVAILABLE", "无法调起系统程序，服务可能运行在没有图形界面的环境中");
        }
    }

    /** 按平台生成命令；参数逐项传入，不经过 Shell。 */
    static List<String> command(String os, String action, Path path) {
        String name = os.toLowerCase(Locale.ROOT);
        String file = path.toString();
        boolean open = action.equals("open");
        if (name.startsWith("windows"))
            // "/select," 与路径分开传递，含空格的路径由 ProcessBuilder 单独加引号，explorer 才能正确解析。
            return open ? List.of("explorer.exe", file) : List.of("explorer.exe", "/select,", file);
        if (name.startsWith("mac")) return open ? List.of("open", file) : List.of("open", "-R", file);
        return List.of("xdg-open", open ? file : path.getParent().toString());
    }

    static boolean openable(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && OPENABLE.contains(name.substring(dot + 1)) && plainArgument(path);
    }

    /** explorer.exe 会把逗号、分号当作参数分隔符截断路径，使截断后的可执行文件绕过扩展名检查。 */
    private static boolean plainArgument(String path) {
        return path.indexOf(',') < 0 && path.indexOf(';') < 0;
    }

    private FileChange applied(String changeId) {
        FileChange change = changes.find(changeId);
        if (!change.status().equals("APPLIED"))
            throw new HarnessException("CONFLICT", "只有已写入工作区的文件可以查看或打开");
        return change;
    }

    private Artifact describe(Run run, FileChange change, boolean created) {
        String state = "UNAVAILABLE";
        long size = 0;
        Instant modified = null;
        try {
            Path path = locate(run, change);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) state = "MISSING";
            else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                state = "FILE";
                size = attrs.size();
                modified = attrs.lastModifiedTime().toInstant();
            }
        } catch (IOException | RuntimeException error) {
            // 工作区目录被删除、父目录缺失或路径变成链接时无法安全判断，统一标记为不可访问。
        }
        return new Artifact(
                change.id(), change.path(), created ? "create" : "replace", state, size, modified, openable(change.path()));
    }

    private static Path locate(Run run, FileChange change) throws IOException {
        WorkspaceRoots.Target location = WorkspaceRoots.target(run.workspace(), change.path());
        return new WorkspaceFileEditor(location.root()).resolve(location.relative());
    }

    private static void start(List<String> command) throws IOException {
        new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(Redirect.DISCARD)
                .start()
                .getOutputStream()
                .close();
    }
}
