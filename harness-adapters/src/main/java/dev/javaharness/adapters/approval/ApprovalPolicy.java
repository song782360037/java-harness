package dev.javaharness.adapters.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.run.ApprovalMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Decides who settles a proposed command from the run's captured mode and the saved user rules. */
public final class ApprovalPolicy {
    public record Rules(long revision, List<String> deny, List<String> ask, List<String> allow) {}

    private record Stored(List<String> deny, List<String> ask, List<String> allow) {}

    public enum Verdict {
        APPROVE,
        ASK,
        REJECT
    }

    /**
     * decidedBy is null while the user still has to decide; detail null keeps the default waiting text.
     * reviewable marks a smart-mode escalation that the optional reviewer chain may settle instead of the user.
     */
    public record Decision(Verdict verdict, String decidedBy, String detail, boolean reviewable) {
        public Decision(Verdict verdict, String decidedBy, String detail) {
            this(verdict, decidedBy, detail, false);
        }
    }

    private static final int MAX_RULES = 100;
    private static final int MAX_RULE_LENGTH = 200;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ApprovalPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Rules rules() {
        return jdbc.queryForObject(
                "SELECT revision,rules FROM harness_approval_policy WHERE id=1",
                (rs, row) -> {
                    try {
                        Stored stored = json.readValue(rs.getString("rules"), Stored.class);
                        return new Rules(rs.getLong("revision"), stored.deny(), stored.ask(), stored.allow());
                    } catch (JsonProcessingException error) {
                        throw new IllegalStateException("Invalid approval rules", error);
                    }
                });
    }

    /** Replace all rule lists when the revision still matches. */
    public Rules save(long revision, List<String> deny, List<String> ask, List<String> allow) {
        var stored = new Stored(normalize(deny), normalize(ask), normalize(allow));
        for (String rule : stored.allow()) {
            String invalid = CommandRules.invalidAllow(rule);
            if (invalid != null) throw new HarnessException("INVALID", invalid);
        }
        try {
            if (jdbc.update(
                            "UPDATE harness_approval_policy SET revision=revision+1,rules=? WHERE id=1 AND revision=?",
                            json.writeValueAsString(stored),
                            revision)
                    == 0) throw new HarnessException("CONFLICT", "审批规则已被修改，请刷新后重试");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
        return rules();
    }

    public ApprovalMode mode(String runId) {
        return ApprovalMode.valueOf(
                jdbc.queryForObject("SELECT approval_mode FROM harness_run WHERE id=?", String.class, runId));
    }

    /**
     * Hard guard first in every mode; manual mode leaves the rest to the user. Deny and ask rules also bind
     * full access; smart mode approves only simple commands matching an allow rule or the read-only list, and
     * marks the remaining escalations reviewable.
     */
    public Decision command(String runId, String command) {
        String danger = CommandRules.danger(command);
        if (danger != null) return new Decision(Verdict.ASK, null, "高危命令，需要人工确认：" + danger);
        ApprovalMode mode = mode(runId);
        if (mode == ApprovalMode.MANUAL) return new Decision(Verdict.ASK, null, null);
        Rules rules = rules();
        String deny = CommandRules.matchAny(rules.deny(), command);
        if (deny != null)
            return new Decision(Verdict.REJECT, "RULE", "命中拒绝规则「" + deny + "」，命令未执行；不要换一种写法重试");
        String ask = CommandRules.matchAny(rules.ask(), command);
        if (ask != null) return new Decision(Verdict.ASK, null, "命中询问规则「" + ask + "」，需要人工确认");
        if (mode == ApprovalMode.FULL_ACCESS) return new Decision(Verdict.APPROVE, "FULL_ACCESS", "完全访问模式自动批准");
        if (CommandRules.compound(command))
            return new Decision(Verdict.ASK, null, "包含组合、重定向或变量等结构，需要人工确认", true);
        String allow = CommandRules.matchSimple(rules.allow(), command);
        if (allow != null) return new Decision(Verdict.APPROVE, "RULE", "命中允许规则「" + allow + "」，已自动批准");
        if (CommandRules.readOnly(command)) return new Decision(Verdict.APPROVE, "RULE", "只读命令，已自动批准");
        return new Decision(Verdict.ASK, null, null, true);
    }

    private static List<String> normalize(List<String> rules) {
        if (rules == null) return List.of();
        var unique = new LinkedHashSet<String>();
        for (String rule : rules) {
            String raw = rule == null ? "" : rule.strip();
            String value = raw.replaceAll("\\s+", " ");
            if (value.isEmpty()) continue;
            if (value.length() > MAX_RULE_LENGTH || raw.chars().anyMatch(Character::isISOControl))
                throw new HarnessException("INVALID", "每条规则需为 1–" + MAX_RULE_LENGTH + " 个字符且不含换行");
            unique.add(value);
        }
        if (unique.size() > MAX_RULES) throw new HarnessException("INVALID", "每类规则最多 " + MAX_RULES + " 条");
        return new ArrayList<>(unique);
    }
}
