package dev.javaharness.server.service;

import static dev.javaharness.adapters.acp.AcpProcess.JSON;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Stores trusted local ACP program configurations and their optimistic revisions. */
@Service
public class ExternalAgentCatalog {
    public record Profile(
            String id,
            String alias,
            String name,
            String preset,
            String executable,
            List<String> arguments,
            boolean workspaceWrite,
            boolean enabled,
            long revision) {}

    public record Command(String id, String description, String agentId, boolean enabled) {}

    private static final Set<String> PRESETS = Set.of("codex", "claude", "custom");
    private static final TypeReference<List<String>> ARGUMENTS = new TypeReference<>() {};
    private static final RowMapper<Profile> PROFILE = (rs, row) -> new Profile(
            rs.getString("id"),
            rs.getString("alias"),
            rs.getString("name"),
            rs.getString("preset"),
            rs.getString("executable"),
            decode(rs.getString("arguments")),
            rs.getBoolean("workspace_write"),
            rs.getBoolean("enabled"),
            rs.getLong("revision"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore sessions;

    public ExternalAgentCatalog(JdbcTemplate jdbc, PlatformTransactionManager transactions, SessionStore sessions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.sessions = sessions;
    }

    /** List profiles without running their configured processes. */
    public List<Profile> list() {
        return jdbc.query("SELECT * FROM harness_external_agent ORDER BY alias", PROFILE);
    }

    /** Expose only command names, not local executable paths or arguments, to the input picker. */
    public List<Command> commands() {
        return list().stream()
                .map(agent -> new Command(agent.alias(), agent.name(), agent.id(), agent.enabled()))
                .toList();
    }

    /** Resolve the leading alias, keeping namespaced plugin commands out of ACP routing. */
    public Profile resolve(String alias) {
        var candidates = jdbc.query("SELECT * FROM harness_external_agent WHERE alias=?", PROFILE, alias);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /** Validate the launch profile before persisting it or testing it. */
    public static Profile validate(Profile form) {
        if (form == null || form.preset() == null || !PRESETS.contains(form.preset())) throw invalid("无效的智能体类型");
        String alias = form.alias() == null ? "" : form.alias().strip();
        if (!alias.matches("[a-z0-9][a-z0-9-]{0,31}")) throw invalid("别名须为 1–32 个小写字母、数字或连字符");
        if ((alias.equals("codex") || alias.equals("claude")) && !alias.equals(form.preset()))
            throw invalid("该别名保留给对应的内置预设");
        String name = form.name() == null ? "" : form.name().strip();
        if (name.isEmpty() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw invalid("名称须为 1–80 个字符");
        String executable = form.executable() == null ? "" : form.executable().strip();
        try {
            if (executable.isEmpty()
                    || executable.length() > 2048
                    || executable.codePoints().anyMatch(Character::isISOControl)
                    || (!Path.of(executable).isAbsolute() && !executable.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")))
                throw invalid("请输入程序名称或可执行文件的绝对路径");
        } catch (InvalidPathException error) {
            throw invalid("无效的可执行文件路径");
        }
        List<String> args = form.arguments() == null ? List.of() : form.arguments();
        if (args.size() > 32
                || args.stream()
                        .anyMatch(arg -> arg == null
                                || arg.length() > 2048
                                || arg.codePoints().anyMatch(Character::isISOControl))) throw invalid("无效的启动参数");
        return new Profile(
                form.id(),
                alias,
                name,
                form.preset(),
                executable,
                List.copyOf(args),
                form.workspaceWrite(),
                form.enabled(),
                form.revision());
    }

    /** Save a new profile or replace exactly the revision the user edited. */
    public Profile save(Profile form) {
        var agent = validate(form);
        synchronized (sessions) {
            return tx.execute(ignored -> {
                var occupied = resolve(agent.alias());
                if (occupied != null && !occupied.id().equals(agent.id()))
                    throw new HarnessException("CONFLICT", "该智能体别名已存在");
                String id = agent.id();
                if (id == null || id.isBlank()) {
                    id = UUID.randomUUID().toString();
                    jdbc.update(
                            """
                            INSERT INTO harness_external_agent(id,alias,name,preset,executable,arguments,workspace_write,enabled,revision)
                            VALUES (?,?,?,?,?,?,?,?,1)
                            """,
                            id,
                            agent.alias(),
                            agent.name(),
                            agent.preset(),
                            agent.executable(),
                            write(agent.arguments()),
                            agent.workspaceWrite(),
                            agent.enabled());
                } else {
                    int changed = jdbc.update(
                            """
                            UPDATE harness_external_agent SET alias=?,name=?,preset=?,executable=?,arguments=?,workspace_write=?,enabled=?,revision=revision+1
                            WHERE id=? AND revision=?
                            """,
                            agent.alias(),
                            agent.name(),
                            agent.preset(),
                            agent.executable(),
                            write(agent.arguments()),
                            agent.workspaceWrite(),
                            agent.enabled(),
                            id,
                            agent.revision());
                    if (changed != 1) throw stale();
                    // An edited executable or trust mode must not reuse the old program's session.
                    if (occupied == null || !sameLaunch(occupied, agent))
                        jdbc.update("DELETE FROM harness_external_session WHERE agent_id=?", id);
                }
                return resolve(agent.alias());
            });
        }
    }

    /** Remove a saved profile without deleting its historical runs. */
    public void remove(String id, long revision) {
        synchronized (sessions) {
            if (jdbc.update("DELETE FROM harness_external_agent WHERE id=? AND revision=?", id, revision) != 1)
                throw stale();
        }
    }

    static boolean sameLaunch(Profile left, Profile right) {
        return left.preset().equals(right.preset())
                && left.executable().equals(right.executable())
                && left.arguments().equals(right.arguments())
                && left.workspaceWrite() == right.workspaceWrite();
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Cannot encode ACP data", error);
        }
    }

    private static List<String> decode(String value) {
        try {
            return JSON.readValue(value, ARGUMENTS);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid ACP launch profile", error);
        }
    }

    private static HarnessException stale() {
        return new HarnessException("CONFLICT", "外部智能体配置已变更，请刷新后重试");
    }

    private static HarnessException invalid(String message) {
        return new HarnessException("INVALID", message);
    }
}
