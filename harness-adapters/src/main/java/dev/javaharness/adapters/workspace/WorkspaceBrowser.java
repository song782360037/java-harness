package dev.javaharness.adapters.workspace;

import dev.javaharness.spi.HarnessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 工作区只读文件树与代码预览读取器，复用 {@link WorkspacePaths} 的路径安全与敏感文件过滤规则。
 */
public final class WorkspaceBrowser {
    static final int MAX_ENTRIES = 500;
    static final int MAX_PREVIEW_BYTES = 256 * 1024;

    public record TreeNode(int root, String path, String name, boolean directory, long size) {}

    public record DirectoryView(int root, String path, List<TreeNode> entries, boolean truncated) {}

    public record FilePreview(
            int root,
            String path,
            String name,
            long size,
            boolean binary,
            boolean truncated,
            String content,
            int totalLines) {}

    private WorkspaceBrowser() {}

    /** 列出工作区指定根目录（1-based）下某个目录的直接子项；目录优先、按名称忽略大小写排序。 */
    public static DirectoryView list(String workspace, int rootIndex, String relativePath) throws IOException {
        WorkspacePaths paths = pathsFor(workspace, rootIndex);
        String normalized = normalizeRelative(relativePath);
        Path target = normalized.isEmpty() ? paths.root() : resolve(paths, normalized);
        BasicFileAttributes targetAttrs =
                Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!targetAttrs.isDirectory()) throw new HarnessException("INVALID", "目标路径不是目录");

        var entries = new ArrayList<TreeNode>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path child : stream) {
                if (paths.excluded(child)) continue;
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException unreadable) {
                    continue;
                }
                if (attrs.isSymbolicLink() || attrs.isOther()) continue;
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(new TreeNode(
                        rootIndex,
                        paths.relative(child),
                        child.getFileName().toString(),
                        attrs.isDirectory(),
                        attrs.isDirectory() ? 0L : attrs.size()));
            }
        }
        entries.sort(Comparator.comparing(TreeNode::directory)
                .reversed()
                .thenComparing(TreeNode::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TreeNode::name));
        return new DirectoryView(rootIndex, normalized, List.copyOf(entries), truncated);
    }

    /** 只读预览工作区内的单个文件；超过 256 KB 截断，二进制文件返回 binary=true。 */
    public static FilePreview read(String workspace, int rootIndex, String relativePath) throws IOException {
        WorkspacePaths paths = pathsFor(workspace, rootIndex);
        String normalized = normalizeRelative(relativePath);
        if (normalized.isEmpty()) throw new HarnessException("INVALID", "请指定要预览的文件路径");
        Path target = resolve(paths, normalized);
        BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new HarnessException("INVALID", "只能预览普通文件");

        long size = attrs.size();
        boolean truncated = size > MAX_PREVIEW_BYTES;
        int toRead = (int) Math.min(size, MAX_PREVIEW_BYTES);
        byte[] bytes = readHead(target, toRead);
        String name = target.getFileName().toString();
        if (containsNul(bytes)) {
            return new FilePreview(rootIndex, normalized, name, size, true, truncated, "", 0);
        }
        String text = decodeUtf8(bytes, truncated);
        if (text == null) {
            return new FilePreview(rootIndex, normalized, name, size, true, truncated, "", 0);
        }
        int totalLines = text.isEmpty() ? 0 : countLines(text);
        return new FilePreview(rootIndex, normalized, name, size, false, truncated, text, totalLines);
    }

    private static WorkspacePaths pathsFor(String workspace, int rootIndex) throws IOException {
        if (workspace == null || workspace.isBlank()) throw new HarnessException("INVALID", "当前未绑定工作区");
        List<String> roots = WorkspaceRoots.directories(workspace);
        if (rootIndex < 1 || rootIndex > roots.size()) throw new HarnessException("INVALID", "工作区目录编号无效");
        return new WorkspacePaths(Path.of(roots.get(rootIndex - 1)).toRealPath());
    }

    private static Path resolve(WorkspacePaths paths, String relative) throws IOException {
        try {
            return paths.resolve(relative);
        } catch (NoSuchFileException missing) {
            throw new HarnessException("NOT_FOUND", "文件或目录不存在");
        } catch (IllegalArgumentException | IOException rejected) {
            throw new HarnessException("INVALID", "路径无效或不在允许的工作区范围内");
        }
    }

    private static String normalizeRelative(String relativePath) {
        if (relativePath == null) return "";
        String value = relativePath.strip();
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static byte[] readHead(Path target, int limit) throws IOException {
        if (limit == 0) return new byte[0];
        byte[] buffer = new byte[limit];
        int offset = 0;
        try (InputStream in = Files.newInputStream(target)) {
            while (offset < limit) {
                int read = in.read(buffer, offset, limit - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return offset == limit ? buffer : Arrays.copyOf(buffer, offset);
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    /** 截断读取时可能切在多字节 UTF-8 字符中间，最多回退 3 字节重试解码。 */
    private static String decodeUtf8(byte[] bytes, boolean truncated) {
        int maxTrim = truncated ? Math.min(3, bytes.length) : 0;
        for (int trim = 0; trim <= maxTrim; trim++) {
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, bytes.length - trim))
                        .toString();
            } catch (CharacterCodingException ignored) {
                // Retry with 1 fewer trailing byte when truncated.
            }
        }
        return null;
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        return lines;
    }
}
