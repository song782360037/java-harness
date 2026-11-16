package dev.javaharness.server.service;

import dev.javaharness.adapters.flow.FlowStore;
import dev.javaharness.adapters.flow.FlowStore.Flow;
import dev.javaharness.adapters.flow.FlowStore.Item;
import dev.javaharness.adapters.flow.FlowStore.Step;
import dev.javaharness.server.service.FlowDefinition.Edge;
import dev.javaharness.server.service.FlowDefinition.Node;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelSelection;
import dev.javaharness.spi.model.TokenCounter;
import dev.javaharness.spi.run.AgentEngine;
import dev.javaharness.spi.run.ApprovalMode;
import dev.javaharness.spi.run.Run;
import dev.javaharness.spi.run.RunExtension;
import dev.javaharness.spi.run.RunStatus;
import dev.javaharness.spi.session.Session;
import dev.javaharness.spi.session.SessionStore;
import dev.javaharness.spi.workspace.WorkspaceTools;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 流水线流转：一件事项同一时刻只在一个节点上。岗位节点是事项隐藏会话中的一次普通运行，结束后按其结论走对应分支；
 * 人工节点、岗位没有给出明确结论或运行失败时，事项停下等待用户选择下一步或重试；到达终点即完成。
 * 事项的进度全部保存在数据库，内存只记录运行与事项的对应关系。锁顺序为调度器 → 引擎 → 会话存储。
 */
public final class FlowOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(FlowOrchestrator.class);
    // A loop of rejections ends here instead of running forever.
    static final int MAX_STEPS = 40;
    private static final int MAX_INPUT = 20_000;

    private final FlowStore flows;
    private final SessionStore sessions;
    private final TransactionTemplate tx;
    private final AgentEngine engine;
    // The item of every agent run still in flight.
    private final Map<String, String> runItems = new HashMap<>();

    public FlowOrchestrator(FlowStore flows, SessionStore sessions, TransactionTemplate tx, AgentEngine engine) {
        this.flows = flows;
        this.sessions = sessions;
        this.tx = tx;
        this.engine = engine;
        // The engine already marked the runs of a previous process as interrupted.
        update(flows::recover);
    }

    /** Trigger the flow: create an item with its hidden session and enter the first node. */
    public synchronized Item start(String flowId, String title, String input) {
        // Control characters would break the item block of the agent prompts.
        String name = title == null ? "" : title.replaceAll("\\p{Cntrl}", " ").strip();
        if (name.isEmpty() || name.length() > 100) throw new HarnessException("INVALID", "事项标题需为 1–100 个字符");
        String text = input == null ? "" : input.strip();
        if (text.length() > MAX_INPUT) throw new HarnessException("INVALID", "事项说明不能超过 " + MAX_INPUT + " 个字符");
        Flow flow = flows.get(flowId);
        FlowDefinition definition = FlowDefinition.parse(flow.definition());
        Item item = write(() -> {
            String sessionId = sessions.createSessionInWorkspace(
                            FlowPrompts.clip(flow.name() + " · " + name, 99), flow.workspaceId())
                    .id();
            return flows.addItem(
                    flowId, name, text, sessionId, flow.definition(), definition.trigger().key());
        });
        enter(item.id(), definition, definition.outgoing(definition.trigger().key()).getFirst().to());
        return flows.item(item.id());
    }

    /** The user picks the branch of a waiting item; a node with a single exit needs no label. */
    public synchronized Item decide(String itemId, String label, String note) {
        Item item = flows.item(itemId);
        if (!item.status().equals("WAITING")) throw new HarnessException("CONFLICT", "这件事项当前不需要处理");
        String remark = note == null ? "" : note.replaceAll("\\p{Cntrl}", " ").strip();
        if (remark.length() > 400) throw new HarnessException("INVALID", "说明不能超过 400 个字符");
        FlowDefinition definition = FlowDefinition.parse(item.definition());
        var outgoing = definition.outgoing(item.nodeKey());
        Edge edge = outgoing.size() == 1 && (label == null || label.isBlank())
                ? outgoing.getFirst()
                : outgoing.stream()
                        .filter(value -> value.label().equals(label))
                        .findFirst()
                        .orElseThrow(() -> new HarnessException("INVALID", "请选择有效的下一步"));
        Step step = flows.lastStep(itemId);
        boolean human = definition.node(item.nodeKey()).type().equals("HUMAN");
        // A human step keeps the user's note as its summary; an agent step keeps the agent's reply. An item
        // interrupted before its first node has no step yet.
        if (step != null)
            update(() -> flows.updateStep(
                    step.id(),
                    "DONE",
                    edge.label(),
                    human ? remark : step.summary(),
                    human ? "" : FlowPrompts.DECIDED + (remark.isEmpty() ? "" : "：" + remark)));
        enter(itemId, definition, edge.to());
        return flows.item(itemId);
    }

    /** Run the agent node of a waiting item again. */
    public synchronized Item retry(String itemId) {
        Item item = flows.item(itemId);
        if (!item.status().equals("WAITING")) throw new HarnessException("CONFLICT", "这件事项当前不需要处理");
        FlowDefinition definition = FlowDefinition.parse(item.definition());
        if (!definition.node(item.nodeKey()).type().equals("AGENT"))
            throw new HarnessException("INVALID", "只有岗位环节可以重试");
        // The attempt that gave no verdict is over; only the new step stays open.
        Step previous = flows.lastStep(itemId);
        if (previous != null && previous.status().equals("WAITING"))
            update(() -> flows.updateStep(previous.id(), "FAILED", "", previous.summary(), previous.detail()));
        enter(itemId, definition, item.nodeKey());
        return flows.item(itemId);
    }

    /** Stop a running or waiting item; its agent run is cancelled. */
    public synchronized Item stop(String itemId) {
        Item item = flows.item(itemId);
        if (!item.status().equals("RUNNING") && !item.status().equals("WAITING"))
            throw new HarnessException("CONFLICT", "这件事项已经结束");
        Step step = flows.lastStep(itemId);
        boolean open = step != null && (step.status().equals("RUNNING") || step.status().equals("WAITING"));
        update(() -> {
            if (open) flows.updateStep(step.id(), "CANCELLED", "", step.summary(), "已停止");
            flows.updateItem(itemId, "STOPPED", item.nodeKey(), "已停止", item.steps());
        });
        if (item.status().equals("RUNNING") && step != null && step.runId() != null) {
            runItems.remove(step.runId());
            try {
                engine.cancel(step.runId());
            } catch (RuntimeException error) {
                // The item is already stopped; a run that settled meanwhile no longer needs cancelling.
                log.warn("Flow item {} could not cancel run {}", itemId, step.runId(), error);
            }
        }
        return flows.item(itemId);
    }

    /** Delete an item that is not running, with its hidden session. */
    public synchronized void deleteItem(String itemId) {
        Item item = flows.item(itemId);
        if (item.status().equals("RUNNING")) throw new HarnessException("BUSY", "事项正在流转，请停止后再删除");
        update(() -> {
            deleteSession(item.sessionId());
            flows.deleteItem(itemId);
        });
    }

    /** Delete a flow with its items and their hidden sessions; rejected while an item is running. */
    public synchronized void delete(String flowId) {
        flows.get(flowId);
        if (flows.counts(flowId).running() > 0) throw new HarnessException("BUSY", "有事项正在流转，请停止后再删除");
        update(() -> {
            for (String sessionId : flows.sessionIds(flowId)) deleteSession(sessionId);
            flows.delete(flowId);
        });
    }

    /** A session already gone does not block removing its item. */
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

    /** An agent run reached its final status: follow its verdict, or wait for the user when there is none. */
    public synchronized void settled(Run run) {
        String itemId = runItems.remove(run.id());
        if (itemId == null) return;
        try {
            Item item = flows.item(itemId);
            Step step = flows.lastStep(itemId);
            // A stopped item ignores the run it cancelled.
            if (!item.status().equals("RUNNING") || step == null || !run.id().equals(step.runId())) return;
            FlowDefinition definition = FlowDefinition.parse(item.definition());
            Node node = definition.node(item.nodeKey());
            if (run.status() != RunStatus.COMPLETED) {
                String outcome = switch (run.status()) {
                            case CANCELLED -> "已停止";
                            case TIMED_OUT -> "超时";
                            case INTERRUPTED -> "已中断";
                            default -> "失败";
                        }
                        + (run.detail().isBlank() ? "" : "：" + run.detail());
                update(() -> {
                    flows.updateStep(
                            step.id(),
                            run.status() == RunStatus.CANCELLED ? "CANCELLED" : "FAILED",
                            "",
                            "",
                            FlowPrompts.clip(outcome, 499));
                    park(item,"「" + node.name() + "」未完成（" + outcome + "），可重试或直接选择下一步");
                });
                return;
            }
            String reply = sessions.conversation(run.id()).getLast().content().strip();
            var outgoing = definition.outgoing(node.key());
            Edge edge = outgoing.size() == 1 ? outgoing.getFirst() : FlowPrompts.verdict(reply, outgoing);
            if (edge == null) {
                update(() -> {
                    flows.updateStep(step.id(), "WAITING", "", reply, "没有给出明确结论");
                    park(item,"「" + node.name() + "」没有给出明确结论，请你选择下一步");
                });
                return;
            }
            update(() -> flows.updateStep(step.id(), "DONE", edge.label(), reply, ""));
            enter(itemId, definition, edge.to());
        } catch (RuntimeException error) {
            log.warn("Flow item {} could not follow run {}", itemId, run.id(), error);
            // An item left running would never move again.
            try {
                Item item = flows.item(itemId);
                if (item.status().equals("RUNNING")) update(() -> park(item,"流转出错，可重试当前环节或直接选择下一步"));
            } catch (RuntimeException ignored) {
                // The item is gone or the store is failing; restart recovery makes it wait.
            }
        }
    }

    /** The item stops at its node until the user retries it or picks a branch. */
    private void park(Item item, String detail) {
        flows.updateItem(item.id(), "WAITING", item.nodeKey(), FlowPrompts.clip(detail, 499), item.steps());
    }

    /** Move the item onto a node: finish at an end, wait at a human node, or start the agent's run. */
    private void enter(String itemId, FlowDefinition definition, String nodeKey) {
        Item item = flows.item(itemId);
        Node node = definition.node(nodeKey);
        int steps = item.steps() + 1;
        if (steps > MAX_STEPS) {
            update(() -> flows.updateItem(
                    itemId, "FAILED", item.nodeKey(), "已超过最多 " + MAX_STEPS + " 步，流程可能陷入了循环", item.steps()));
            return;
        }
        if (node.type().equals("END")) {
            update(() -> {
                flows.addStep(itemId, nodeKey, node.name(), "END", "DONE", null);
                flows.updateItem(itemId, "COMPLETED", nodeKey, node.name(), steps);
            });
            return;
        }
        if (node.type().equals("HUMAN")) {
            update(() -> {
                flows.addStep(itemId, nodeKey, node.name(), "HUMAN", "WAITING", null);
                flows.updateItem(itemId, "WAITING", nodeKey, "", steps);
            });
            return;
        }
        Run[] submitted = new Run[1];
        try {
            Session session = sessions.snapshot(item.sessionId()).session();
            String prompt = FlowPrompts.step(
                    flows.get(item.flowId()).name(),
                    definition,
                    node,
                    item,
                    flows.steps(itemId),
                    !session.workspace().isEmpty());
            Run run = engine.submit(
                            item.sessionId(),
                            UUID.randomUUID().toString(),
                            prompt,
                            "NORMAL",
                            new ModelSelection(node.modelProfileId(), node.reasoningEffort()),
                            ApprovalMode.valueOf(node.approvalMode()))
                    .run();
            submitted[0] = run;
            runItems.put(run.id(), itemId);
            update(() -> {
                flows.addStep(itemId, nodeKey, node.name(), "AGENT", "RUNNING", run.id());
                flows.updateItem(itemId, "RUNNING", nodeKey, "", steps);
            });
        } catch (RuntimeException error) {
            // For example a deleted model profile: the user fixes it and retries. A run that started but could not
            // be recorded is cancelled, so the item never stays running without a step to settle.
            String reason = error instanceof HarnessException ? error.getMessage() : "提交运行失败";
            if (!(error instanceof HarnessException)) log.warn("Flow item {} could not enter {}", itemId, nodeKey, error);
            if (submitted[0] != null) {
                runItems.remove(submitted[0].id());
                try {
                    engine.cancel(submitted[0].id());
                } catch (RuntimeException ignored) {
                    // Already settled or gone.
                }
            }
            update(() -> {
                Step step = flows.addStep(itemId, nodeKey, node.name(), "AGENT", "FAILED", null);
                flows.updateStep(step.id(), "FAILED", "", "", FlowPrompts.clip(reason, 499));
                flows.updateItem(
                        itemId,
                        "WAITING",
                        nodeKey,
                        FlowPrompts.clip("无法启动「" + node.name() + "」：" + reason, 499),
                        steps);
            });
        }
    }

    /** Let the runs of item sessions report their settlement here; ordinary sessions keep their extension. */
    public RunExtension decorate(Session session, RunExtension extension) {
        if (flows.itemBySession(session.id()) == null) return extension;
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
                return extension.context(counter, maxTokens);
            }

            public void settled(Run run) {
                try {
                    extension.settled(run);
                } finally {
                    FlowOrchestrator.this.settled(run);
                }
            }

            public void close() {
                extension.close();
            }
        };
    }

    private void update(Runnable change) {
        write(() -> {
            change.run();
            return null;
        });
    }

    /** Flow writes share the session store's coordination lock and one transaction. */
    private <T> T write(Supplier<T> change) {
        synchronized (sessions) {
            return tx.execute(status -> change.get());
        }
    }
}
