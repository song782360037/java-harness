package dev.javaharness.server.service;

import dev.javaharness.spi.HarnessException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** User-directed local directory picker. It returns directory names only, never file contents. */
@Component
public final class WorkspaceDirectories {
    public record Entry(String name, String path) {}

    public record Listing(String path, String parent, List<Entry> directories, boolean truncated) {}

    public String normalize(String input) {
        if (input == null || input.isBlank()) return "";
        if (input.length() > 4096) throw invalid();
        try {
            Path path = Path.of(input.strip());
            if (!path.isAbsolute()) throw invalid();
            path = path.toRealPath();
            if (!Files.isDirectory(path) || !Files.isReadable(path)) throw invalid();
            return path.toString();
        } catch (java.io.IOException | IllegalArgumentException | SecurityException error) {
            throw invalid();
        }
    }

    private static HarnessException invalid() {
        return new HarnessException("INVALID", "目录不可访问，请输入本机现有目录的绝对路径");
    }

    public Listing list(String input) {
        if (input == null || input.isBlank()) {
            var roots = new ArrayList<Entry>();
            FileSystems.getDefault()
                    .getRootDirectories()
                    .forEach(path -> roots.add(new Entry(path.toString(), path.toString())));
            return new Listing("", "", roots, false);
        }
        Path parent = Path.of(normalize(input));
        var entries = new ArrayList<Entry>();
        boolean truncated = false;
        int scanned = 0;
        try (var stream = Files.newDirectoryStream(parent)) {
            for (Path child : stream) {
                if (++scanned > 4000 || entries.size() >= 200) {
                    truncated = true;
                    break;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(child)) {
                    entries.add(new Entry(child.getFileName().toString(), child.toString()));
                }
            }
        } catch (java.io.IOException | SecurityException error) {
            throw invalid();
        }
        entries.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return new Listing(
                parent.toString(),
                parent.getParent() == null ? "" : parent.getParent().toString(),
                entries,
                truncated);
    }
}
