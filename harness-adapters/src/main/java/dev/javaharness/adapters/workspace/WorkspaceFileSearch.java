package dev.javaharness.adapters.workspace;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Fuzzy file and directory lookup for composer mentions, under the same name policy as the workspace tools. */
public final class WorkspaceFileSearch {
    /** One match; root is the 1-based directory number used by multi-root tool calls. */
    public record Entry(int root, String path, boolean directory) {}

    private record Scored(Entry entry, int score) {}

    static final int SCAN_LIMIT = 20_000;

    private WorkspaceFileSearch() {}

    /** Breadth-first scan of every workspace directory; an empty query lists the shallowest entries. */
    public static List<Entry> search(String workspace, String query, int limit) throws IOException {
        String needle = query.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (needle.startsWith("/")) needle = needle.substring(1);
        var matches = new ArrayList<Scored>();
        int scanned = 0;
        List<String> roots = WorkspaceRoots.directories(workspace);
        for (int index = 0; index < roots.size() && scanned < SCAN_LIMIT; index++) {
            var paths = new WorkspacePaths(Path.of(roots.get(index)).toRealPath());
            var queue = new ArrayDeque<Path>();
            queue.add(paths.root());
            while (!queue.isEmpty() && scanned < SCAN_LIMIT) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(queue.poll())) {
                    for (Path child : children) {
                        if (++scanned > SCAN_LIMIT) break;
                        if (paths.excluded(child)) continue;
                        BasicFileAttributes attrs =
                                Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attrs.isSymbolicLink() || attrs.isOther()) continue;
                        if (attrs.isDirectory()) queue.add(child);
                        String relative = paths.relative(child);
                        int score = score(relative, needle);
                        if (score >= 0)
                            matches.add(new Scored(new Entry(index + 1, relative, attrs.isDirectory()), score));
                    }
                } catch (IOException unreadable) {
                    // An unreadable directory is skipped like the directory listing tool does.
                }
            }
        }
        if (needle.isEmpty()) return matches.stream().limit(limit).map(Scored::entry).toList();
        return matches.stream()
                .sorted(Comparator.comparingInt(Scored::score)
                        .thenComparingInt(value -> value.entry().path().length())
                        .thenComparing(value -> value.entry().path()))
                .limit(limit)
                .map(Scored::entry)
                .toList();
    }

    /** Lower is better: exact name, name prefix, name substring, path substring, then path subsequence; -1 misses. */
    static int score(String relative, String needle) {
        if (needle.isEmpty()) return 0;
        String path = relative.toLowerCase(Locale.ROOT);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.equals(needle)) return 0;
        if (name.startsWith(needle)) return 1;
        if (name.contains(needle)) return 2;
        if (path.contains(needle)) return 3;
        int at = 0;
        for (int i = 0; i < path.length() && at < needle.length(); i++) if (path.charAt(i) == needle.charAt(at)) at++;
        return at == needle.length() ? 4 : -1;
    }
}
