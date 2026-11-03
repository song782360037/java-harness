package dev.javaharness.adapters.question;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/** Questions are durable; only the original tool worker waits for and consumes an answer. */
public final class QuestionService {
    public static final int MAX_PER_RUN = 8;
    public static final int MAX_QUESTION = 500;
    public static final int MAX_LABEL = 60;
    public static final int MAX_DESCRIPTION = 200;
    public static final int MAX_NOTE = 2000;
    // An unanswered question gives up well before a default run deadline, leaving time for a final answer.
    public static final long MAX_WAIT_MINUTES = 5;

    public record Option(String label, String description) {}

    /** status is PENDING, ANSWERED, SKIPPED, CANCELLED or INTERRUPTED; selected and note are set once answered. */
    public record Question(
            String id,
            String runId,
            String question,
            List<Option> options,
            boolean multiSelect,
            String status,
            List<String> selected,
            String note,
            String detail,
            Instant createdAt) {}

    private record Answer(List<String> selected, String note) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SessionStore sessions;
    private final RowMapper<Question> mapper = (rs, row) -> {
        String answer = rs.getString("answer");
        Answer value = answer.isEmpty() ? new Answer(List.of(), "") : read(answer, new TypeReference<>() {});
        return new Question(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("question"),
                read(rs.getString("options"), new TypeReference<>() {}),
                rs.getBoolean("multi_select"),
                rs.getString("status"),
                value.selected(),
                value.note(),
                rs.getString("detail"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    };

    public QuestionService(JdbcTemplate jdbc, TransactionTemplate tx, SessionStore sessions) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sessions = sessions;
        synchronized (sessions) {
            for (var question : jdbc.query("SELECT * FROM harness_question WHERE status='PENDING'", mapper))
                settle(question, "INTERRUPTED", null, "服务中断，问题已失效");
        }
    }

    public List<Question> list(String runId) {
        synchronized (sessions) {
            sessions.run(runId);
            return jdbc.query("SELECT * FROM harness_question WHERE run_id=? ORDER BY created_at,id", mapper, runId);
        }
    }

    /** Persist a question of a running run; a retried call id returns the question it already created. */
    public Question ask(String runId, String callId, String question, List<Option> options, boolean multiSelect) {
        synchronized (sessions) {
            requireRunning(runId);
            var existing = jdbc.query(
                    "SELECT * FROM harness_question WHERE run_id=? AND call_id=?", mapper, runId, callId);
            if (!existing.isEmpty()) return existing.getFirst();
            if (question == null || question.isBlank() || question.length() > MAX_QUESTION)
                throw new HarnessException("INVALID", "question 需为 1–" + MAX_QUESTION + " 个字符");
            if (options == null || options.size() < 2 || options.size() > 4)
                throw new HarnessException("INVALID", "options 需提供 2–4 个选项");
            var labels = new HashSet<String>();
            for (var option : options) {
                if (option.label() == null
                        || option.label().isBlank()
                        || option.label().length() > MAX_LABEL
                        || option.description() == null
                        || option.description().length() > MAX_DESCRIPTION)
                    throw new HarnessException(
                            "INVALID", "选项 label 需为 1–" + MAX_LABEL + " 个字符，description 不超过 " + MAX_DESCRIPTION + " 个字符");
                if (!labels.add(option.label())) throw new HarnessException("INVALID", "选项 label 不能重复");
            }
            Integer count =
                    jdbc.queryForObject("SELECT COUNT(*) FROM harness_question WHERE run_id=?", Integer.class, runId);
            if (count != null && count >= MAX_PER_RUN)
                throw new HarnessException("INVALID", "单次任务最多向用户提问 " + MAX_PER_RUN + " 次；请基于已有信息继续，并在回答中说明假设");
            String id = UUID.randomUUID().toString();
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO harness_question(id,run_id,call_id,question,options,multi_select,status,created_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """,
                        id,
                        runId,
                        callId,
                        question,
                        write(options),
                        multiSelect,
                        "PENDING",
                        OffsetDateTime.now(ZoneOffset.UTC));
                sessions.questionsUpdated(runId);
            });
            return get(id);
        }
    }

    /** Record the user's answer: chosen labels, a free-text note, or both. */
    public Question answer(String id, List<String> selected, String note) {
        synchronized (sessions) {
            var value = get(id);
            if (!value.status().equals("PENDING")) throw new HarnessException("CONFLICT", "该问题已处理");
            requireRunning(value.runId());
            if (selected != null && selected.stream().anyMatch(java.util.Objects::isNull)) throw new HarnessException("INVALID", "选项不能为空");
            List<String> chosen = selected == null ? List.of() : List.copyOf(selected);
            String text = note == null ? "" : note.strip();
            if (chosen.isEmpty() && text.isEmpty()) throw new HarnessException("INVALID", "请选择一个选项或填写回答");
            if (text.length() > MAX_NOTE) throw new HarnessException("INVALID", "回答不超过 " + MAX_NOTE + " 个字符");
            if (!value.multiSelect() && chosen.size() > 1) throw new HarnessException("INVALID", "该问题只能选择一个选项");
            var labels = value.options().stream().map(Option::label).toList();
            if (!labels.containsAll(chosen) || new HashSet<>(chosen).size() != chosen.size())
                throw new HarnessException("INVALID", "选项不存在或重复");
            return settle(value, "ANSWERED", new Answer(chosen, text), "");
        }
    }

    /** The user declines to answer; the model continues on its own judgment. */
    public Question skip(String id) {
        synchronized (sessions) {
            var value = get(id);
            if (value.status().equals("SKIPPED")) return value;
            if (!value.status().equals("PENDING")) throw new HarnessException("CONFLICT", "该问题已处理");
            requireRunning(value.runId());
            return settle(value, "SKIPPED", null, "用户跳过了这个问题");
        }
    }

    /** Wait until the question is settled, the run stops or the wait limit passes; waiting counts toward the deadline. */
    public WorkspaceTools.Result await(Question question, ModelProvider.Cancellation cancellation) throws Exception {
        long expiresAt = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(MAX_WAIT_MINUTES);
        try {
            while (true) {
                cancellation.check();
                synchronized (sessions) {
                    var value = get(question.id());
                    if (value.status().equals("PENDING") && System.nanoTime() - expiresAt >= 0)
                        value = settle(
                                value,
                                "CANCELLED",
                                null,
                                "超过 " + MAX_WAIT_MINUTES + " 分钟未获回答，问题已失效；请按你的最佳判断继续，并在回答中说明所做的假设");
                    if (!value.status().equals("PENDING")) return result(value);
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException error) {
            // Clear interrupt for JDBC settlement; the engine still owns the run's cancellation reason.
            Thread.interrupted();
            synchronized (sessions) {
                var value = get(question.id());
                if (value.status().equals("PENDING")) settle(value, "CANCELLED", null, "任务停止或超时，问题已取消");
            }
            throw error;
        } catch (Exception error) {
            synchronized (sessions) {
                var value = get(question.id());
                if (value.status().equals("PENDING")) settle(value, "INTERRUPTED", null, "读取回答失败，问题已失效");
            }
            throw error;
        }
    }

    private Question get(String id) {
        var values = jdbc.query("SELECT * FROM harness_question WHERE id=?", mapper, id);
        if (values.isEmpty()) throw new HarnessException("NOT_FOUND", "问题不存在");
        return values.getFirst();
    }

    private Question settle(Question value, String status, Answer answer, String detail) {
        return tx.execute(ignored -> {
            jdbc.update(
                    "UPDATE harness_question SET status=?,answer=?,detail=? WHERE id=?",
                    status,
                    answer == null ? "" : write(answer),
                    detail,
                    value.id());
            sessions.questionsUpdated(value.runId());
            return get(value.id());
        });
    }

    private void requireRunning(String runId) {
        if (sessions.run(runId).status() != RunStatus.RUNNING)
            throw new HarnessException("CONFLICT", "任务已停止，不能再提问或回答");
    }

    private static WorkspaceTools.Result result(Question value) {
        return switch (value.status()) {
            case "ANSWERED" -> new WorkspaceTools.Result(
                    true,
                    "用户回答：" + (value.selected().isEmpty() ? "（未选择选项）" : String.join("；", value.selected()))
                            + (value.note().isEmpty() ? "" : "\n补充说明：" + value.note()));
            case "SKIPPED" -> new WorkspaceTools.Result(false, "用户跳过了这个问题；请按你的最佳判断继续，并在回答中说明所做的假设，不要重复提问。");
            default -> new WorkspaceTools.Result(false, value.detail().isEmpty() ? "问题未获回答" : value.detail());
        };
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private static <T> T read(String value, TypeReference<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored question is not valid JSON", error);
        }
    }
}
