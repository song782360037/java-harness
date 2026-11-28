package dev.javaharness.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.adapters.bot.BotLanStore.Device;
import dev.javaharness.adapters.bot.BotStore.BotChange;
import dev.javaharness.adapters.bot.BotStore.BotLimits;
import dev.javaharness.adapters.bot.BotStore.Entry;
import dev.javaharness.adapters.bot.BotStore.Fact;
import dev.javaharness.adapters.bot.BotStore.LayoutItem;
import dev.javaharness.adapters.bot.BotStore.MiniApp;
import dev.javaharness.adapters.bot.BotStore.MiniAppVersion;
import dev.javaharness.adapters.bot.BotStore.PendingBotApproval;
import dev.javaharness.adapters.bot.BotStore.Playbook;
import dev.javaharness.adapters.bot.BotStore.Routine;
import dev.javaharness.adapters.bot.BotStore.Section;
import dev.javaharness.adapters.bot.BotStore.Task;
import dev.javaharness.server.service.BotDebugService;
import dev.javaharness.server.service.BotLanServer;
import dev.javaharness.server.service.BotMiniAppService;
import dev.javaharness.server.service.BotOrchestrator;
import dev.javaharness.server.service.BotRoutineScheduler;
import dev.javaharness.server.service.BotService;
import dev.javaharness.server.service.BotSkillService;
import dev.javaharness.server.service.ExternalAgentCatalog;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.workspace.CommandExecution;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bot 工作台 REST 与全局 SSE 接口。 */
@RestController
@RequestMapping("/api")
public class BotController {
    private final BotService bots;
    private final BotOrchestrator orchestrator;
    private final BotRoutineScheduler routines;
    private final BotMiniAppService miniApps;
    private final BotSkillService botSkills;
    private final BotLanServer lanServer;
    private final BotDebugService debug;
    private final ObjectMapper json;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(16);

    public BotController(
            BotService bots,
            BotOrchestrator orchestrator,
            BotRoutineScheduler routines,
            BotMiniAppService miniApps,
            BotSkillService botSkills,
            BotLanServer lanServer,
            BotDebugService debug,
            ObjectMapper json) {
        this.bots = bots;
        this.orchestrator = orchestrator;
        this.routines = routines;
        this.miniApps = miniApps;
        this.botSkills = botSkills;
        this.lanServer = lanServer;
        this.debug = debug;
        this.json = json;
    }

    public record PostMessage(
            String requestId,
            String content,
            Long quoteSeq,
            List<String> attachmentIds,
            String attachmentsJson,
            String viewingMiniAppId,
            Boolean fromMiniApp) {}

    public record FactRequest(String scope, String content) {}

    public record SectionDraft(String name, Boolean collapsed, Integer ordinal) {}

    public record LayoutRequest(List<LayoutItem> items) {}

    public record DeleteEntriesRequest(List<Long> seqs) {}

    public record StartTaskRequest(
            String title, String instruction, String playbookId, String modelProfileId, String miniAppId) {}

    public record RestoreVersionRequest(int version) {}

    public record ToggleRoutineRequest(boolean enabled) {}

    public record ImportSkillRequest(String skillId) {}

    public record LanSettingsRequest(boolean enabled, int port, boolean allowRemoteApproval, long revision) {}

    public record PairConfirmRequest(String code, String deviceName) {}

    public record SkillOfferSourceRequest(String source, String targetBotId) {}

    public record SkillOfferAnswerRequest(List<String> names) {}

    public record SkillRemoveRequest(List<String> names, String targetBotId) {}

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
                id,
                request.requestId(),
                request.content(),
                request.quoteSeq(),
                request.attachmentIds(),
                request.attachmentsJson(),
                request.viewingMiniAppId() == null ? "" : request.viewingMiniAppId(),
                Boolean.TRUE.equals(request.fromMiniApp()));
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

    @PostMapping(value = "/bots/{id}/unread", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> unread(@PathVariable String id) {
        bots.markUnread(id);
        return Map.of("unread", true);
    }

    @PostMapping(value = "/bots/seen-all", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> seenAll() {
        bots.markAllSeen();
        return Map.of("seenAll", true);
    }

    @GetMapping("/bots/{id}/entries/search")
    public List<Entry> searchEntries(
            @PathVariable String id,
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return bots.searchEntries(id, query, limit);
    }

    @GetMapping("/bots/usage")
    public BotDebugService.BotUsageReport botUsage(@RequestParam(value = "days", defaultValue = "7") int days) {
        return debug.usage(days);
    }

    @GetMapping("/bots/{id}/skills")
    public List<BotSkillService.SkillView> listBotSkills(@PathVariable String id) {
        return botSkills.listViews(id);
    }

    @PostMapping(value = "/bots/{id}/skills/offer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotSkillService.SkillOfferView offerBotSkillFromSource(
            @PathVariable String id, @RequestBody SkillOfferSourceRequest request) {
        String target = request.targetBotId() == null || request.targetBotId().isBlank() ? id : request.targetBotId();
        return botSkills.offerFromPanelSource(target, request.source());
    }

    @PostMapping(value = "/bots/{id}/skills/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BotSkillService.SkillOfferView uploadBotSkillArchive(
            @PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new HarnessException("INVALID", "请选择要上传的 .zip 或 .skill 压缩包");
        return botSkills.offerFromUpload(id, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping(value = "/bots/{id}/skills/offers/{offerId}/answer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotSkillService.SkillOfferView answerBotSkillOffer(
            @PathVariable String id,
            @PathVariable String offerId,
            @RequestBody SkillOfferAnswerRequest request) {
        return botSkills.answerOffer(id, offerId, request.names());
    }

    @GetMapping("/bot-skill-offers/{offerId}/{skillName}/files")
    public List<BotSkillService.SkillFileView> offerSkillFiles(
            @PathVariable String offerId, @PathVariable String skillName) {
        return botSkills.listOfferFiles(offerId, skillName);
    }

    @GetMapping("/bot-skill-offers/{offerId}/{skillName}/file")
    public BotSkillService.SkillFileContent offerSkillFile(
            @PathVariable String offerId, @PathVariable String skillName, @RequestParam("path") String path) {
        return botSkills.readOfferFile(offerId, skillName, path);
    }

    @GetMapping("/bots/{id}/skills/{skillName}/files")
    public List<BotSkillService.SkillFileView> installedSkillFiles(
            @PathVariable String id, @PathVariable String skillName) {
        return botSkills.listInstalledFiles(id, skillName);
    }

    @GetMapping("/bots/{id}/skills/{skillName}/file")
    public BotSkillService.SkillFileContent installedSkillFile(
            @PathVariable String id, @PathVariable String skillName, @RequestParam("path") String path) {
        return botSkills.readInstalledFile(id, skillName, path);
    }

    @PostMapping(value = "/bots/{id}/skills/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> removeBotSkills(
            @PathVariable String id, @RequestBody SkillRemoveRequest request) {
        List<String> removed = botSkills.removeSkills(id, request.targetBotId(), request.names());
        return Map.of("removed", removed);
    }

    @PostMapping(value = "/bots/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BotService.BotCard uploadAvatar(@PathVariable String id, @RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) throw new HarnessException("INVALID", "请选择要上传的头像图片");
        return bots.uploadAvatar(id, file.getBytes());
    }

    @PostMapping(value = "/bots/{id}/avatar/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotService.BotCard deleteAvatar(@PathVariable String id) {
        return bots.deleteAvatar(id);
    }

    @GetMapping("/bots/{id}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable String id) throws IOException {
        BotService.RawFile file = bots.readAvatar(id);
        byte[] bytes = Files.readAllBytes(file.path());
        String etag = "\"" + file.filename() + "-" + file.sizeBytes() + "-"
                + Files.getLastModifiedTime(file.path()).toMillis() + "\"";
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CONTENT_TYPE, file.mediaType())
                .body(bytes);
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
                request.miniAppId() == null ? "" : request.miniAppId(),
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

    @GetMapping("/bots/{id}/files/raw")
    public ResponseEntity<byte[]> fileRaw(@PathVariable String id, @RequestParam("path") String path)
            throws IOException {
        BotService.RawFile file = bots.readRawFile(id, path);
        byte[] bytes = Files.readAllBytes(file.path());
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .header(HttpHeaders.CONTENT_TYPE, file.mediaType())
                .body(bytes);
    }

    @GetMapping("/bots/{id}/debug/context")
    public BotDebugService.ContextView debugContext(@PathVariable String id) {
        return debug.context(id);
    }

    @GetMapping("/bots/{id}/debug/traces")
    public List<BotDebugService.TraceView> debugTraces(@PathVariable String id) {
        return debug.traces(id);
    }

    @GetMapping("/bots/{id}/debug/changes")
    public List<BotChange> debugChanges(@PathVariable String id) {
        return debug.changes(id);
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

    @GetMapping("/bot-apps/{appId}/versions")
    public List<MiniAppVersion> miniAppVersions(@PathVariable String appId) {
        return miniApps.versions(appId);
    }

    @GetMapping("/bot-apps/{appId}/versions/{version}")
    public BotMiniAppService.AppBundle miniAppVersion(@PathVariable String appId, @PathVariable int version) {
        return miniApps.bundleVersion(appId, version);
    }

    @PostMapping(value = "/bot-apps/{appId}/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MiniApp restoreMiniAppVersion(@PathVariable String appId, @RequestBody RestoreVersionRequest request) {
        return miniApps.restoreVersion(appId, request.version());
    }

    @GetMapping("/bot-apps/{appId}/export")
    public ResponseEntity<byte[]> exportMiniApp(
            @PathVariable String appId, @RequestParam(value = "version", required = false) Integer version) {
        BotMiniAppService.ExportFile exported = miniApps.exportStandaloneHtml(appId, version);
        String encoded = URLEncoder.encode(exported.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(exported.bytes());
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

    @GetMapping("/bots/{id}/facts")
    public BotService.FactsBundle facts(@PathVariable String id) {
        return bots.facts(id);
    }

    @PostMapping(value = "/bots/{id}/facts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Fact addFact(@PathVariable String id, @RequestBody FactRequest request) {
        return bots.addFact(id, request.scope(), request.content());
    }

    @PostMapping(value = "/bot-facts/{factId}/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> deleteFact(@PathVariable String factId) {
        bots.deleteFact(factId);
        return Map.of("deleted", true);
    }

    @GetMapping("/bots/{id}/approvals")
    public List<PendingBotApproval> botApprovals(@PathVariable String id) {
        return bots.pendingApprovals(id);
    }

    @GetMapping("/bots/approvals")
    public List<PendingBotApproval> allBotApprovals() {
        return bots.allPendingApprovals();
    }

    @PostMapping(value = "/bot-approvals/{commandId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommandExecution approveBotCommand(@PathVariable String commandId) {
        return bots.approveBotCommand(commandId);
    }

    @PostMapping(value = "/bot-approvals/{commandId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommandExecution rejectBotCommand(@PathVariable String commandId) {
        return bots.rejectBotCommand(commandId);
    }

    @GetMapping("/bots/settings/search")
    public BotService.WebSearchSettingsView webSearchSettings() {
        return bots.webSearchSettings();
    }

    @PostMapping(value = "/bots/settings/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotService.WebSearchSettingsView saveWebSearchSettings(
            @RequestBody BotService.WebSearchSettingsDraft request) {
        return bots.saveWebSearchSettings(request);
    }

    @GetMapping("/bots/settings/limits")
    public BotLimits botLimits() {
        return bots.limits();
    }

    @PostMapping(value = "/bots/settings/limits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BotLimits saveBotLimits(@RequestBody BotService.BotLimitsDraft request) {
        return bots.saveLimits(request);
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
        Map<String, String> lastStreamSignature = new HashMap<>();
        try {
            while (!done.get() && !Thread.currentThread().isInterrupted() && System.nanoTime() < expires) {
                var activeStreams = orchestrator.activeChatSnapshots();
                var activeKeys = new HashMap<String, Boolean>();
                for (var stream : activeStreams) {
                    activeKeys.put(stream.botId(), Boolean.TRUE);
                    String sig = stream.runId() + ":" + stream.status() + ":" + stream.output().length();
                    if (!sig.equals(lastStreamSignature.get(stream.botId()))) {
                        emitter.send(SseEmitter.event().name("bot-stream").data(stream));
                        lastStreamSignature.put(stream.botId(), sig);
                    }
                }
                lastStreamSignature.keySet().removeIf(botId -> !activeKeys.containsKey(botId));

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
                Thread.sleep(orchestrator.hasLiveWork() ? 60 : 250);
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
