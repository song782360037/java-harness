package dev.javaharness.server.web;

import dev.javaharness.adapters.group.GroupStore.Member;
import dev.javaharness.adapters.group.GroupStore.Message;
import dev.javaharness.server.service.GroupChatService;
import dev.javaharness.server.service.GroupOrchestrator;
import dev.javaharness.server.service.GroupTemplates;
import dev.javaharness.spi.HarnessException;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 群聊接口：群与成员的增删改、发送消息、停止协作，以及变更通知 SSE。
 * SSE 只通知"群聊视图可能已变化"，客户端收到后重新读取快照。
 */
@RestController
@RequestMapping("/api")
public class GroupChatController {
    private final GroupChatService groups;
    private final GroupOrchestrator orchestrator;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(16);

    public GroupChatController(GroupChatService groups, GroupOrchestrator orchestrator) {
        this.groups = groups;
        this.orchestrator = orchestrator;
    }

    public record PostMessage(String requestId, String content) {}

    @GetMapping("/group-templates")
    public List<GroupTemplates.Template> templates() {
        return GroupTemplates.ALL;
    }

    @GetMapping("/groups")
    public List<GroupChatService.Summary> list() {
        return groups.list();
    }

    @PostMapping(value = "/groups", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public GroupChatService.Snapshot create(@RequestBody GroupChatService.GroupDraft request) {
        return groups.create(request);
    }

    @GetMapping("/groups/{id}")
    public GroupChatService.Snapshot snapshot(@PathVariable String id) {
        return groups.snapshot(id);
    }

    @PostMapping(value = "/groups/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public GroupChatService.Snapshot update(@PathVariable String id, @RequestBody GroupChatService.GroupDraft request) {
        return groups.update(id, request);
    }

    /** Delete the group with its members' hidden sessions; rejected while members are speaking. */
    @PostMapping(value = "/groups/{id}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> delete(@PathVariable String id) {
        groups.delete(id);
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/groups/{id}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Member createMember(@PathVariable String id, @RequestBody GroupChatService.MemberDraft request) {
        return groups.createMember(id, request);
    }

    @PostMapping(value = "/groups/{id}/members/{memberId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Member updateMember(
            @PathVariable String id, @PathVariable String memberId, @RequestBody GroupChatService.MemberDraft request) {
        return groups.updateMember(id, memberId, request);
    }

    @PostMapping(value = "/groups/{id}/members/{memberId}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deleteMember(@PathVariable String id, @PathVariable String memberId) {
        groups.deleteMember(id, memberId);
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/groups/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Message post(@PathVariable String id, @RequestBody PostMessage request) {
        return orchestrator.post(id, request.requestId(), request.content());
    }

    @PostMapping(value = "/groups/{id}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> stop(@PathVariable String id) {
        groups.version(id);
        orchestrator.stop(id);
        return Map.of("stopping", true);
    }

    @GetMapping(value = "/groups/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, HttpServletResponse response) {
        String initial = groups.version(id); // Validate before response headers are committed.
        if (!slots.tryAcquire()) throw new HarnessException("UNAVAILABLE", "实时连接过多，请稍后重试");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean done = new AtomicBoolean();
        emitter.onCompletion(() -> done.set(true));
        emitter.onTimeout(() -> done.set(true));
        emitter.onError(error -> done.set(true));
        try {
            streams.execute(() -> follow(id, initial, emitter, done));
        } catch (RejectedExecutionException error) {
            slots.release();
            throw new HarnessException("UNAVAILABLE", "服务正在停止");
        }
        return emitter;
    }

    /** Poll the group version; changes are coalesced so a busy group notifies at most every 300 ms. */
    private void follow(String id, String version, SseEmitter emitter, AtomicBoolean done) {
        long heartbeat = 0;
        long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        try {
            while (!done.get() && !Thread.currentThread().isInterrupted() && System.nanoTime() < expires) {
                String current = groups.version(id);
                if (!current.equals(version)) {
                    emitter.send(SseEmitter.event().name("group-event").data(Map.of("version", current)));
                    version = current;
                }
                if (System.nanoTime() - heartbeat > TimeUnit.SECONDS.toNanos(5)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    heartbeat = System.nanoTime();
                }
                Thread.sleep(300);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException | HarnessException error) {
            // Disconnected clients reconnect and reread the snapshot; a deleted group ends the stream.
        } catch (Exception error) {
            emitter.completeWithError(error);
        } finally {
            slots.release();
            emitter.complete();
        }
    }

    @PreDestroy
    public void close() {
        streams.shutdownNow();
    }
}
