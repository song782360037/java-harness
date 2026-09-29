package dev.javaharness.adapters;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/**
 * Path rules shared by the read-only workspace tools: confinement to one real root, rejection of
 * symbolic links and special files, and the excluded-name policy. Shared so that a second tool
 * family cannot drift from the rules the file tools already enforce.
 */
final class WorkspacePaths {
    static final Set<String> EXCLUDED = Set.of(".git", ".idea", "node_modules", "target", "dist", ".ssh", ".aws", ".azure");
    private final Path root;

    WorkspacePaths(Path root) { this.root = root; }

    Path root() { return root; }

    boolean excluded(Path path) {
        for (Path part : root.relativize(path)) if (protectedName(part.toString())) return true;
        return false;
    }

    /** Same policy for a slash-separated path reported by git, which need not exist on disk. */
    boolean excludedName(String slashRelative) {
        for (String part : slashRelative.split("/")) if (protectedName(part)) return true;
        return false;
    }

    private static boolean protectedName(String part) {
        String name = part.toLowerCase(Locale.ROOT);
        return EXCLUDED.contains(name) || name.startsWith(".env") || name.endsWith(".pem") || name.endsWith(".key")
                || name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".jks");
    }

    /** Validates a model-supplied workspace-relative, slash-separated path that must exist. */
    Path resolve(String relative) throws IOException {
        Path path = normalize(relative);
        verify(path, true);
        return path;
    }

    /**
     * Validates a model-supplied path used only as a git pathspec. Unlike {@link #resolve}, the path
     * may have been deleted, so a missing leaf is accepted; a directory is rejected because git
     * expands it, which would expose excluded names inside it.
     */
    Path resolveFileSpec(String relative) throws IOException {
        Path path = normalize(relative);
        verify(path, false);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Directory pathspec rejected");
        return path;
    }

    private Path normalize(String relative) {
        if (relative.length() > 2048 || relative.contains(":") || relative.contains("\\") || relative.startsWith("/")
                || relative.indexOf('\0') >= 0) throw new IllegalArgumentException("Relative slash-separated paths required");
        return root.resolve(relative).normalize();
    }

    void verify(Path path, boolean requireExisting) throws IOException {
        if (!path.startsWith(root) || excluded(path)) throw new IOException("Outside permitted tree");
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                // Without this segment nothing below it exists either, so no link can be crossed.
                if (requireExisting) throw missing;
                return;
            }
            if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Unsupported link or special file");
        }
        if (!path.toRealPath().startsWith(root)) throw new IOException("Resolved outside root");
    }

    String relative(Path path) { return root.relativize(path).toString().replace('\\', '/'); }
}
