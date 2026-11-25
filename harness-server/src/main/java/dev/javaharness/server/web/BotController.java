package dev.javaharness.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.bot.BotLanStore.Device;
import dev.javaharness.adapters.bot.BotStore.LayoutItem;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Section;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.server.service.BotLanServer;
import dev.javaharness.server.service.BotMiniAppService;
import dev.javaharness.server.service.BotOrchestrator;
import dev.javaharness.server.service.BotRoutineScheduler;
import dev.javaharness.server.service.BotService;
import dev.javaharness.server.service.ExternalAgentCatalog;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bot 工作台 REST 与全局 SSE 接口。 */
@RestController
@RequestMapping("/api")
public class BotController {
    private final BotService bots;
    private final BotOrchestrator orchestrator;
    private final BotRoutineScheduler routines;
    private final BotMiniAppService miniApps;
    private final BotLanServer lanServer;
    private final ObjectMapper json;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(16);

    public BotController(
            BotService bots,
            BotOrchestrator orchestrator,
            BotRoutineScheduler routines,
            BotMiniAppService miniApps,
            BotLanServer lanServer,
            ObjectMapper json) {
        this.bots = bots;
        this.orchestrator = orchestrator;
        this.routines = routines;
        this.miniApps = miniApps;
        this.lanServer = lanServer;
        this.json = json;
    }

    public record PostMessage(String requestId, String content, Long quoteSeq, String attachmentsJson) {}

    public record SectionDraft(String name, Boolean collapsed, Integer ordinal) {}

    public record LayoutRequest(List<LayoutItem> items) {}

    public record DeleteEntriesRequest(List<Long> seqs) {}

    public record StartTaskRequest(String title, String instruction, String playbookId, String modelProfileId) {}

    public record ToggleRoutineRequest(boolean enabled) {}

    public record ImportSkillRequest(String skillId) {}

    public record LanSettingsRequest(boolean enabled, int port, boolean allowRemoteApproval, long revision) {}

    public record PairConfirmRequest(String code, String deviceName) {}

    @GetMapping("/bots/workspace")
    public BotService.WorkspaceSnapshot workspace() {
        return bots.workspaceSnapshot();
    }

    @PostMapping(value = "/bots/onboard", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotService.WorkspaceSnapshot onboard(@RequestBody BotService.OnboardDraft request) {
        return bots.onboard(request);
    }

    @PostMapping(value = "/bots", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BotService.BotCard create(@RequestBody BotService.BotDraft request) {
        return bots.createBot(request);
    }

    @PostMapping(value = "/bots/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotService.BotCard update(@PathVariable String id, @RequestBody BotService.BotDraft request) {
        return bots.updateBot(id, request);
    }

    @PostMapping(value = "/bots/{id}/archive", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> archive(@PathVariable String id) {
        bots.archiveBot(id);
        return Map.of("archived", true);
    }

    @PostMapping(value = "/bots/sections", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Section createSection(@RequestBody SectionDraft request) {
        return bots.createSection(request.name());
    }

    @PostMapping(value = "/bots/sections/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Section updateSection(@PathVariable String id, @RequestBody SectionDraft request) {
        return bots.updateSection(id, request.name(), request.collapsed(), request.ordinal());
    }

    @PostMapping(value = "/bots/sections/{id}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deleteSection(@PathVariable String id) {
        bots.deleteSection(id);
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/bots/layout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> updateLayout(@RequestBody LayoutRequest request) {
        bots.updateLayout(request.items());
        return Map.of("saved", true);
    }

    @GetMapping("/bots/{id}/thread")
    public BotService.ThreadSnapshot thread(@PathVariable String id) {
        return bots.threadSnapshot(id);
    }

    @PostMapping(value = "/bots/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BotOrchestrator.PostResult postMessage(@PathVariable String id, @RequestBody PostMessage request) {
        return orchestrator.postMessage(
                id, request.requestId(), request.content(), request.quoteSeq(), request.attachmentsJson());
    }

    @PostMapping(value = "/bots/{id}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> stop(@PathVariable String id) {
        orchestrator.stopChat(id);
        return Map.of("stopping", true);
    }

    @PostMapping(value = "/bots/{id}/seen", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> seen(@PathVariable String id) {
        bots.markSeen(id);
        return Map.of("seen", true);
    }

    @PostMapping(value = "/bots/{id}/entries/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deleteEntries(@PathVariable String id, @RequestBody DeleteEntriesRequest request) {
        bots.deleteEntries(id, request.seqs());
        return Map.of("deleted", true);
    }

    @PostMapping(value = "/bots/{id}/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Task startTask(@PathVariable String id, @RequestBody StartTaskRequest request) {
        return orchestrator.startTask(
                id,
                id,
                request.title(),
                request.instruction(),
                request.playbookId() == null ? "" : request.playbookId(),
                request.modelProfileId() == null ? "" : request.modelProfileId(),
                false);
    }

    @GetMapping("/bot-tasks/{taskId}")
    public BotService.TaskDetail taskDetail(@PathVariable String taskId) {
        return bots.taskDetail(taskId);
    }

    @PostMapping(value = "/bot-tasks/{taskId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Task cancelTask(@PathVariable String taskId) {
        return orchestrator.cancelTask(taskId);
    }

    @PostMapping(value = "/bots/{id}/playbooks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Playbook savePlaybook(@PathVariable String id, @RequestBody BotService.PlaybookDraft request) {
        return bots.savePlaybook(id, request);
    }

    @PostMapping(value = "/bot-playbooks/{playbookId}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deletePlaybook(@PathVariable String playbookId) {
        bots.deletePlaybook(playbookId);
        return Map.of("deleted", true);
    }

    @GetMapping("/bots/skills")
    public List<BotService.SkillSummary> skills() {
        return bots.availableSkills();
    }

    @GetMapping("/bots/external-agents")
    public List<ExternalAgentCatalog.Profile> externalAgents() {
        return bots.availableExternalAgents();
    }

    @PostMapping(value = "/bots/{id}/playbooks/import-skill", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Playbook importSkill(@PathVariable String id, @RequestBody ImportSkillRequest request) {
        return bots.importSkillAsPlaybook(id, request.skillId());
    }

    @GetMapping("/bots/{id}/files/content")
    public BotService.FileContent fileContent(@PathVariable String id, @RequestParam("path") String path) {
        return bots.readLibraryFile(id, path);
    }

    @GetMapping("/bots/{id}/routines")
    public List<Routine> routines(@PathVariable String id) {
        return routines.list(id);
    }

    @PostMapping(value = "/bots/{id}/routines", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Routine saveRoutine(@PathVariable String id, @RequestBody BotRoutineScheduler.RoutineDraft request) {
        return routines.save(id, request);
    }

    @PostMapping(value = "/bot-routines/{routineId}/toggle", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Routine toggleRoutine(@PathVariable String routineId, @RequestBody ToggleRoutineRequest request) {
        return routines.toggle(routineId, request.enabled());
    }

    @PostMapping(value = "/bot-routines/{routineId}/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Task runRoutine(@PathVariable String routineId) {
        return routines.runNow(routineId);
    }

    @PostMapping(value = "/bot-routines/{routineId}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deleteRoutine(@PathVariable String routineId) {
        routines.delete(routineId);
        return Map.of("deleted", true);
    }

    @GetMapping("/bot-apps/{appId}")
    public BotMiniAppService.AppBundle miniApp(@PathVariable String appId) {
        return miniApps.bundle(appId);
    }

    @PostMapping(value = "/bot-apps/{appId}/data", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MiniApp saveMiniAppData(@PathVariable String appId, @RequestBody JsonNode data) throws IOException {
        return miniApps.updateAppData(appId, "", json.writeValueAsString(data));
    }

    @PostMapping(value = "/bot-apps/{appId}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deleteMiniApp(@PathVariable String appId) {
        miniApps.deleteApp(appId);
        return Map.of("deleted", true);
    }

    @GetMapping("/bot-lan/status")
    public BotLanServer.LanStatus lanStatus() {
        return lanServer.status();
    }

    @PostMapping(value = "/bot-lan/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotLanServer.LanStatus updateLanSettings(@RequestBody LanSettingsRequest request) {
        return lanServer.updateSettings(
                request.enabled(), request.port(), request.allowRemoteApproval(), request.revision());
    }

    @PostMapping(value = "/bot-lan/pair/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Device confirmLanPairing(@RequestBody PairConfirmRequest request) {
        return lanServer.confirmPairing(request.code(), request.deviceName());
    }

    @PostMapping(value = "/bot-lan/devices/{id}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> revokeLanDevice(@PathVariable String id) {
        lanServer.revokeDevice(id);
        return Map.of("revoked", true);
    }

    @GetMapping(value = "/bots/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        String initial = bots.globalVersion();
        long initialNotice = orchestrator.latestNoticeId();
        if (!slots.tryAcquire()) throw new HarnessException("UNAVAILABLE", "实时连接过多，请稍后重试");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean done = new AtomicBoolean();
        emitter.onCompletion(() -> done.set(true));
        emitter.onTimeout(() -> done.set(true));
        emitter.onError(error -> done.set(true));
        try {
            streams.execute(() -> follow(initial, initialNotice, emitter, done));
        } catch (RejectedExecutionException error) {
            slots.release();
            throw new HarnessException("UNAVAILABLE", "服务正在停止");
        }
        return emitter;
    }

    private void follow(String version, long lastNoticeId, SseEmitter emitter, AtomicBoolean done) {
        long heartbeat = 0;
        long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        try {
            while (!done.get() && !Thread.currentThread().isInterrupted() && System.nanoTime() < expires) {
                String current = bots.globalVersion();
                if (!current.equals(version)) {
                    var freshNotices = orchestrator.recentNotices(lastNoticeId);
                    if (!freshNotices.isEmpty())
                        lastNoticeId = freshNotices.getLast().id();
                    emitter.send(SseEmitter.event()
                            .name("bot-event")
                            .data(Map.of("version", current, "notices", freshNotices)));
                    version = current;
                }
                if (System.nanoTime() - heartbeat > TimeUnit.SECONDS.toNanos(5)) {
                    emitter.send(SseEmitter.event().name("ping").data("ping"));
                    heartbeat = System.nanoTime();
                }
                Thread.sleep(300);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException | HarnessException error) {
            // Client disconnected; it will reconnect and refresh snapshots.
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
