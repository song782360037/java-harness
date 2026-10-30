package dev.javaharness.adapters.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** UTF-8 file snapshots and bounded writes. Paths are revalidated immediately before publication. */
final class WorkspaceFileEditor {
    static final int MAX_BYTES = 1_048_576;
    private final Path root;

    WorkspaceFileEditor(String workspace) throws IOException {
        root = Path.of(workspace).toAbsolutePath().normalize();
        if (!root.equals(root.toRealPath()) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Workspace changed");
    }

    Path resolve(String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > 2048 || relative.startsWith("/")
                || relative.contains("\\") || relative.contains(":") || relative.indexOf('\0') >= 0)
            throw new IOException("Invalid relative path");
        Path current = root;
        for (String part : relative.split("/", -1)) {
            String name = part.toLowerCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || LocalWorkspaceTools.EXCLUDED.contains(name) || name.startsWith(".env")
                    || name.matches(".*\\.(pem|key|p12|pfx|jks)")
                    || name.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))
                throw new IOException("Excluded path");
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther() || !current.toRealPath().startsWith(root))
                    throw new IOException("Unsupported link or special file");
            }
        }
        if (!Files.isDirectory(current.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Parent directory must already exist");
        return current;
    }

    String read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file");
        ByteBuffer bytes = ByteBuffer.allocate(MAX_BYTES + 1);
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > MAX_BYTES) throw new IOException("File too large");
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { /* Bounded snapshot. */ }
        }
        if (bytes.position() > MAX_BYTES) throw new IOException("File too large");
        bytes.flip();
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(bytes).toString();
        validate(value);
        return value;
    }

    static void validate(String text) throws IOException {
        if (text == null || text.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Invalid UTF-8 text or size");
    }

    static String replace(String before, String oldText, String newText) throws IOException {
        if (oldText == null || oldText.isEmpty() || newText == null) throw new IOException("Missing replacement");
        String newline = before.contains("\r\n") ? "\r\n" : "\n";
        String old = oldText.replace("\r\n", "\n").replace("\n", newline);
        String replacement = newText.replace("\r\n", "\n").replace("\n", newline);
        int offset = before.indexOf(old);
        if (offset < 0 || before.indexOf(old, offset + 1) >= 0) throw new IOException("Replacement must match exactly once");
        String after = before.substring(0, offset) + replacement + before.substring(offset + old.length());
        validate(after);
        if (before.equals(after)) throw new IOException("No change");
        return after;
    }

    boolean matches(Path path, String before) throws IOException {
        return before == null ? !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                : Files.exists(path, LinkOption.NOFOLLOW_LINKS) && before.equals(read(path));
    }

    void apply(String relative, String before, String after) throws IOException {
        Path target = resolve(relative);
        Path temp = Files.createTempFile(target.getParent(), ".harness-edit-", ".tmp");
        try {
            Files.writeString(temp, after, StandardCharsets.UTF_8);
            if (before != null) {
                var posix = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (posix != null) Files.setPosixFilePermissions(temp, posix.readAttributes().permissions());
                var acl = Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (acl != null) Files.getFileAttributeView(temp, AclFileAttributeView.class).setAcl(acl.getAcl());
            }
            // Revalidate both the parent chain and content after preparing the replacement.
            target = resolve(relative);
            if (!matches(target, before)) throw new FileConflict();
            if (before == null) {
                // Atomic create-if-absent: never replace a file created by an editor after review.
                Files.createLink(target, temp);
            } else Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static final class FileConflict extends IOException {}
}
