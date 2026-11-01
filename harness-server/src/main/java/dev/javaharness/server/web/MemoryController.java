package dev.javaharness.server.web;

import dev.javaharness.adapters.memory.JdbcMemoryStore;
import dev.javaharness.server.service.MemoryService;
import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.memory.LongTermMemory.Entry;
import dev.javaharness.spi.session.SessionStore;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 记忆管理、图谱关系与任务召回快照接口。 */
@RestController
@RequestMapping("/api")
public class MemoryController {
    private final JdbcMemoryStore store;
    private final MemoryService service;
    private final SessionStore sessions;

    /** 复用会话锁协调来源绑定与会话删除。 */
    public MemoryController(JdbcMemoryStore store, MemoryService service, SessionStore sessions) {
        this.store = store;
        this.service = service;
        this.sessions = sessions;
    }

    public record Page(List<Entry> items, List<JdbcMemoryStore.Relation> relations, int total) {}

    public record Save(
            String workspaceId,
            String title,
            String content,
            String kind,
            List<String> tags,
            String status,
            long revision,
            String sourceRunId,
            String replacesId,
            long replacesRevision) {}

    public record Revision(long revision) {}

    public record Relate(String fromId, String toId, String label) {}

    /** 读取自动记忆的持久化开关与真实处理进度。 */
    @GetMapping("/memory-settings")
    public MemoryService.State settings() {
        return service.state();
    }

    /** 保存开关及生成模型，关闭后停止生成和召回。 */
    @PostMapping("/memory-settings")
    public MemoryService.State configure(@RequestBody JdbcMemoryStore.Settings value) {
        return service.configure(value.enabled(), value.modelProfileId(), value.revision());
    }

    /** 重试失败的历史来源。 */
    @PostMapping("/memory-settings/retry")
    public MemoryService.State retryFailed() {
        return service.retryFailed();
    }

    @GetMapping("/memories")
    /** 查询当前范围的分页记忆及关系。 */
    public Page list(
            @RequestParam(defaultValue = "") String workspaceId,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "200") int limit) {
        service.validateScope(workspaceId);
        if ((!status.isEmpty() && !MemoryService.STATUSES.contains(status))
                || offset < 0
                || limit < 1
                || limit > 200
                || query.length() > 200) throw new HarnessException("INVALID_REQUEST", "无效的记忆查询参数");
        return new Page(
                store.list(workspaceId, status, query, offset, limit),
                store.relations(workspaceId),
                store.count(workspaceId, status, query));
    }

    @GetMapping("/memories/{id}")
    /** 读取搜索或分页之外的关联记忆详情。 */
    public Entry get(@PathVariable String id) {
        return store.get(id);
    }

    @PostMapping("/memories")
    @ResponseStatus(HttpStatus.CREATED)
    /** 创建手动记忆，可绑定成功任务的来源。 */
    public Entry create(@RequestBody Save value) {
        return save("", value);
    }

    @PostMapping("/memories/{id}")
    /** 按版本编辑正文或确认、归档记忆。 */
    public Entry update(@PathVariable String id, @RequestBody Save value) {
        return save(id, value);
    }

    /** 来源仅在新建时绑定，后续编辑不能伪造或覆盖来源。 */
    private Entry save(String id, Save value) {
        synchronized (sessions) {
            return service.save(
                    id,
                    value.workspaceId(),
                    value.title(),
                    value.content(),
                    value.kind(),
                    value.tags(),
                    value.status(),
                    value.revision(),
                    value.sourceRunId(),
                    value.replacesId() == null ? "" : value.replacesId(),
                    value.replacesRevision());
        }
    }

    @PostMapping("/memories/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    /** 按版本删除记忆。 */
    public void delete(@PathVariable String id, @RequestBody Revision value) {
        store.delete(id, value.revision());
    }

    @PostMapping("/memory-relations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    /** 建立或更新同一范围内的显式关系。 */
    public void relate(@RequestBody Relate value) {
        validateRelation(value);
        if (value.label() == null || value.label().isBlank() || value.label().length() > 32)
            throw new HarnessException("INVALID_REQUEST", "关系名称应为1到32个字符");
        store.relate(value.fromId(), value.toId(), value.label().strip());
    }

    @PostMapping("/memory-relations/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    /** 删除指定方向的显式关系。 */
    public void unrelate(@RequestBody Relate value) {
        validateRelation(value);
        store.unrelate(value.fromId(), value.toId());
    }

    /** 缺失关系端点属于客户端输入错误。 */
    private static void validateRelation(Relate value) {
        if (value.fromId() == null
                || value.fromId().isBlank()
                || value.toId() == null
                || value.toId().isBlank()) throw new HarnessException("INVALID_REQUEST", "必须指定关系的两条记忆");
    }

    @GetMapping("/runs/{id}/memories")
    /** 返回任务使用记忆的历史快照。 */
    public List<JdbcMemoryStore.Usage> usage(@PathVariable String id) {
        sessions.run(id);
        return store.usage(id);
    }
}
