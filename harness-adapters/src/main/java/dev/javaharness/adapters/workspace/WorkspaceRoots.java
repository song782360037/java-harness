package dev.javaharness.adapters.workspace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Keeps single-root paths compatible and snapshots multiple roots as a JSON array. */
public final class WorkspaceRoots {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Target(String root, String relative) {}

    private WorkspaceRoots() {}

    /** Decode a persisted root snapshot, including legacy single-directory workspaces. */
    public static List<String> directories(String workspace) {
        if (workspace.isEmpty()) return List.of();
        if (!workspace.startsWith("[")) return List.of(workspace);
        try {
            JsonNode value = JSON.readTree(workspace);
            if (!value.isArray() || value.size() < 2 || value.size() > 16) throw invalid();
            var roots = new ArrayList<String>();
            for (JsonNode root : value) {
                if (!root.isTextual()
                        || root.asText().isBlank()
                        || !Path.of(root.asText()).isAbsolute()) throw invalid();
                roots.add(root.asText());
            }
            return List.copyOf(roots);
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    /** Encode normalized directories without changing the identity of legacy single-root runs. */
    public static String encode(List<String> directories) {
        if (directories == null || directories.isEmpty() || directories.size() > 16) throw invalid();
        var unique = new HashSet<Path>();
        for (String directory : directories) {
            if (directory == null
                    || directory.isBlank()
                    || directory.length() > 4096
                    || !Path.of(directory).isAbsolute()
                    || !unique.add(Path.of(directory))) throw invalid();
        }
        if (directories.size() == 1) return directories.getFirst();
        try {
            return JSON.writeValueAsString(directories);
        } catch (java.io.IOException error) {
            throw invalid();
        }
    }

    /** Multi-root review paths are qualified with @1/, @2/, etc. */
    public static Target target(String workspace, String path) {
        List<String> roots = directories(workspace);
        if (roots.size() == 1) return new Target(roots.getFirst(), path);
        if (path == null || !path.matches("@[1-9][0-9]*/.+")) throw invalid();
        int slash = path.indexOf('/');
        try {
            int index = Integer.parseInt(path.substring(1, slash)) - 1;
            if (index < 0 || index >= roots.size()) throw invalid();
            return new Target(roots.get(index), path.substring(slash + 1));
        } catch (NumberFormatException error) {
            throw invalid();
        }
    }

    private static HarnessException invalid() {
        return new HarnessException("INVALID", "工作区需包含 1–16 个不同的绝对目录，多目录路径需明确目录编号");
    }
}
