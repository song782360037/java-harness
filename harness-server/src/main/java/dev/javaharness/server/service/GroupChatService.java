package dev.javaharness.server.service;

import dev.javaharness.adapters.group.GroupStore;
import dev.javaharness.adapters.group.GroupStore.Group;
import dev.javaharness.adapters.group.GroupStore.Member;
import dev.javaharness.adapters.group.GroupStore.Message;
import dev.javaharness.adapters.group.GroupStore.Round;
import dev.javaharness.adapters.group.GroupStore.RunState;
import dev.javaharness.adapters.group.GroupStore.Task;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunExtension;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 群聊管理：群与成员的增删改，每个成员对应一个隐藏会话；群快照；并为成员会话的运行注入群聊上下文。
 * 写操作在会话存储锁与同一事务内完成，忙碌检查先于存储锁询问调度器。
 */
public final class GroupChatService {
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern MEMBER_NAME = Pattern.compile("[^\\s@]{1,20}");

    /** 群设置；mode 为空时沿用原值（新建为 DISCUSS），templates 仅在新建时使用，按顺序添加这些内置模板成员。 */
    public record GroupDraft(
            String name,
            String color,
            String workspaceId,
            String hostProfileId,
            String hostReasoningEffort,
            Integer maxTurns,
            String mode,
            long revision,
            List<String> templates) {}

    /** 成员设置；template 为空表示自定义成员，新建时其余空字段取模板值。 */
    public record MemberDraft(
            String name,
            String template,
            String color,
            String focus,
            String modelProfileId,
            String reasoningEffort,
            String approvalMode,
            Boolean enabled) {}

    /** 群列表项；busy 表示有进行中的协作。 */
    public record Summary(Group group, int members, boolean busy) {}

    /** 群快照；tasks 为最新一轮的任务计划，讨论模式的轮次为空。 */
    public record Snapshot(
            Group group,
            List<Member> members,
            List<Message> messages,
            Round round,
            List<Task> tasks,
            boolean busy,
            List<RunState> runs,
            String version) {}

    private final GroupStore groups;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final SettingsService settings;
    private final GroupOrchestrator orchestrator;

    public GroupChatService(
            GroupStore groups,
            SessionStore sessions,
            TransactionTemplate tx,
            SettingsService settings,
            GroupOrchestrator orchestrator) {
        this.groups = groups;
        this.sessions = sessions;
        this.tx = tx;
        this.settings = settings;
        this.orchestrator = orchestrator;
    }

    public List<Summary> list() {
        return groups.list().stream()
                .map(group -> new Summary(group, groups.members(group.id()).size(), orchestrator.busy(group.id())))
                .toList();
    }

    public Snapshot snapshot(String id) {
        // Ask the orchestrator before taking the store lock, keeping the orchestrator → store lock order.
        boolean busy = orchestrator.busy(id);
        synchronized (sessions) {
            return tx.execute(status -> {
                Group group = groups.get(id);
                Round round = groups.latestRound(id);
                return new Snapshot(
                        group,
                        groups.members(id),
                        groups.messages(id),
                        round,
                        round == null ? List.of() : groups.tasks(round.id()),
                        busy,
                        groups.runs(id),
                        version(id));
            });
        }
    }

    /** Changes when anything the group view shows may have changed. */
    public String version(String id) {
        // Concatenated, not summed: a group change and a member change must never cancel out.
        return groups.lastSeq(id) + ":" + groups.memberActivity(id);
    }

    public Snapshot create(GroupDraft draft) {
        var templates = draft.templates() == null ? List.<String>of() : draft.templates();
        for (String key : templates)
            if (GroupTemplates.find(key) == null) throw new HarnessException("INVALID", "未知的成员模板");
        if (templates.stream().distinct().count() != templates.size())
            throw new HarnessException("INVALID", "成员模板不能重复");
        Group value = validated("", draft, null);
        String id = write(() -> {
            Group group = groups.save(value);
            for (String key : templates)
                addMember(group, new MemberDraft(null, key, null, null, null, null, null, null));
            return group.id();
        });
        return snapshot(id);
    }

    public Snapshot update(String id, GroupDraft draft) {
        Group existing = groups.get(id);
        Group value = validated(id, draft, existing);
        boolean moved = !value.workspaceId().equals(existing.workspaceId());
        if (moved && orchestrator.busy(id)) throw new HarnessException("BUSY", "群聊正在协作，请停止后再切换工作区");
        write(() -> {
            groups.save(value);
            for (Member member : groups.members(id)) {
                if (moved) sessions.selectWorkspace(member.sessionId(), value.workspaceId());
                if (!value.name().equals(existing.name()))
                    sessions.renameSession(member.sessionId(), title(value, member.name()));
            }
            return null;
        });
        return snapshot(id);
    }

    public void delete(String id) {
        groups.get(id);
        if (orchestrator.busy(id)) throw new HarnessException("BUSY", "群聊正在协作，请停止后再删除");
        write(() -> {
            for (Member member : groups.members(id)) deleteSession(member.sessionId());
            groups.delete(id);
            return null;
        });
    }

    public Member createMember(String groupId, MemberDraft draft) {
        Group group = groups.get(groupId);
        return write(() -> addMember(group, draft));
    }

    public Member updateMember(String groupId, String memberId, MemberDraft draft) {
        Group group = groups.get(groupId);
        Member existing = member(groupId, memberId);
        Member value = validated(draft, existing, existing.template());
        return write(() -> {
            if (!value.name().equals(existing.name()))
                sessions.renameSession(existing.sessionId(), title(group, value.name()));
            return groups.saveMember(value);
        });
    }

    public void deleteMember(String groupId, String memberId) {
        Member member = member(groupId, memberId);
        write(() -> {
            // Rejected while the member is speaking; its earlier messages keep the copied author name.
            deleteSession(member.sessionId());
            groups.deleteMember(memberId);
            return null;
        });
    }

    /** Member sessions are changed only through their group chat; reading them stays allowed. */
    public void requireOrdinary(String sessionId) {
        if (groups.memberBySession(sessionId) != null)
            throw new HarnessException("CONFLICT", "这是群聊成员的会话，请在群聊中管理");
    }

    /** Delete a member's hidden session; one already gone does not block removing the member or group. */
    private void deleteSession(String sessionId) {
        // Checked with a plain read: a failure inside the store's nested transaction would roll back the caller's.
        try {
            sessions.snapshot(sessionId);
        } catch (HarnessException error) {
            if ("NOT_FOUND".equals(error.code())) return;
            throw error;
        }
        sessions.deleteSession(sessionId);
    }

    private Member member(String groupId, String memberId) {
        Member member = groups.member(memberId);
        if (!member.groupId().equals(groupId)) throw new HarnessException("NOT_FOUND", "成员不存在");
        return member;
    }

    /** Create the member's hidden session in the group's workspace, then the member itself. */
    private Member addMember(Group group, MemberDraft draft) {
        var template =
                draft.template() == null || draft.template().isEmpty() ? null : GroupTemplates.find(draft.template());
        if (draft.template() != null && !draft.template().isEmpty() && template == null)
            throw new HarnessException("INVALID", "未知的成员模板");
        var filled = new MemberDraft(
                blank(draft.name()) && template != null ? template.name() : draft.name(),
                template == null ? "" : template.key(),
                blank(draft.color()) && template != null ? template.color() : draft.color(),
                blank(draft.focus()) && template != null ? template.focus() : draft.focus(),
                draft.modelProfileId(),
                draft.reasoningEffort(),
                draft.approvalMode(),
                draft.enabled());
        Member value = validated(filled, null, filled.template());
        String sessionId = sessions.createSessionInWorkspace(title(group, value.name()), group.workspaceId())
                .id();
        return groups.saveMember(new Member(
                "",
                group.id(),
                sessionId,
                value.name(),
                value.template(),
                value.color(),
                value.focus(),
                value.modelProfileId(),
                value.reasoningEffort(),
                value.approvalMode(),
                0,
                value.enabled()));
    }

    private Group validated(String id, GroupDraft draft, Group existing) {
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.isEmpty() || name.length() > 100) throw new HarnessException("INVALID", "群聊名称需为 1–100 个字符");
        String color = blank(draft.color()) ? "#4b705b" : draft.color();
        if (!COLOR.matcher(color).matches()) throw new HarnessException("INVALID", "请选择有效的颜色");
        String workspaceId = draft.workspaceId() == null ? "" : draft.workspaceId();
        if (!workspaceId.isEmpty() && (existing == null || !workspaceId.equals(existing.workspaceId())))
            sessions.workspace(workspaceId);
        String host = blank(draft.hostProfileId()) ? "default" : draft.hostProfileId();
        if (existing == null || !host.equals(existing.hostProfileId())) profile(host);
        checkEffort(draft.hostReasoningEffort());
        int maxTurns = draft.maxTurns() == null ? 12 : draft.maxTurns();
        if (maxTurns < 1 || maxTurns > 50) throw new HarnessException("INVALID", "每轮发言上限需为 1–50 次");
        String mode = blank(draft.mode()) ? existing == null ? "DISCUSS" : existing.mode() : draft.mode();
        if (!mode.equals("DISCUSS") && !mode.equals("TASKS")) throw new HarnessException("INVALID", "不支持的协作模式");
        return new Group(
                id,
                name,
                color,
                workspaceId,
                host,
                draft.hostReasoningEffort(),
                maxTurns,
                mode,
                existing == null ? null : existing.createdAt(),
                draft.revision());
    }

    private Member validated(MemberDraft draft, Member existing, String template) {
        String name = draft.name() == null ? "" : draft.name().strip();
        // The column counts UTF-16 units, so emoji names are bounded by length as well as by code points.
        if (!MEMBER_NAME.matcher(name).matches() || name.length() > 20) throw new HarnessException("INVALID", "成员昵称需为 1–20 个字符，且不含空格和 @");
        if (name.equals("用户") || name.equals("系统")) throw new HarnessException("INVALID", "该昵称已被保留");
        String color = blank(draft.color()) ? "#4b705b" : draft.color();
        if (!COLOR.matcher(color).matches()) throw new HarnessException("INVALID", "请选择有效的颜色");
        String focus = draft.focus() == null ? "" : draft.focus().strip();
        if (focus.isEmpty() || focus.length() > 2000) throw new HarnessException("INVALID", "职责描述需为 1–2000 个字符");
        String profile = blank(draft.modelProfileId()) ? "default" : draft.modelProfileId();
        if (existing == null || !profile.equals(existing.modelProfileId())) profile(profile);
        checkEffort(draft.reasoningEffort());
        ApprovalMode mode =
                draft.approvalMode() == null ? ApprovalMode.MANUAL : ApprovalMode.parse(draft.approvalMode());
        return new Member(
                existing == null ? "" : existing.id(),
                existing == null ? "" : existing.groupId(),
                existing == null ? "" : existing.sessionId(),
                name,
                template,
                color,
                focus,
                profile,
                draft.reasoningEffort(),
                mode.name(),
                existing == null ? 0 : existing.ordinal(),
                draft.enabled() == null || draft.enabled());
    }

    /** A missing model profile is an invalid choice here, not a missing resource. */
    private void profile(String id) {
        try {
            settings.profile(id);
        } catch (HarnessException error) {
            if ("NOT_FOUND".equals(error.code())) throw new HarnessException("INVALID", "所选模型配置不存在，请重新选择");
            throw error;
        }
    }

    private static void checkEffort(String effort) {
        if (effort != null && !ModelSelection.EFFORTS.contains(effort))
            throw new HarnessException("INVALID", "不支持的思考强度");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String title(Group group, String member) {
        String title = group.name() + " · " + member;
        return title.length() <= 100 ? title : title.substring(0, 100);
    }

    private <T> T write(Supplier<T> change) {
        synchronized (sessions) {
            return tx.execute(status -> change.get());
        }
    }

    /**
     * Wrap the extension of a member session: the run sees the group transcript captured now, and its settlement
     * reaches the orchestrator. Ordinary sessions keep their extension.
     */
    public RunExtension decorate(Session session, RunExtension extension) {
        Member self = groups.memberBySession(session.id());
        if (self == null) return extension;
        Group group = groups.get(self.groupId());
        List<Member> members = groups.members(group.id()).stream()
                .filter(member -> member.enabled() || member.id().equals(self.id()))
                .toList();
        List<Message> messages = groups.messages(group.id());
        boolean workspace = !session.workspace().isEmpty();
        return new RunExtension() {
            public String snapshot() {
                return extension.snapshot();
            }

            public String instructions() {
                return extension.instructions();
            }

            public void submitted(String runId) {
                extension.submitted(runId);
            }

            public WorkspaceTools open(String runId, WorkspaceTools local, ModelProvider.Cancellation cancellation)
                    throws Exception {
                return extension.open(runId, local, cancellation);
            }

            public String context(TokenCounter counter, int maxTokens) {
                return GroupPrompts.context(group, self, members, messages, workspace, counter, maxTokens);
            }

            public void settled(Run run) {
                try {
                    extension.settled(run);
                } finally {
                    orchestrator.settled(run);
                }
            }

            public void close() {
                extension.close();
            }
        };
    }
}
