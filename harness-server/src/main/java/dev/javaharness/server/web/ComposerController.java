package dev.javaharness.server.web;

import dev.javaharness.adapters.attachment.AttachmentStore.Attachment;
import dev.javaharness.adapters.workspace.WorkspaceFileSearch;
import dev.javaharness.server.service.ComposerInputService;
import dev.javaharness.server.service.GroupChatService;
import dev.javaharness.server.service.PromptOptimizationService;
import dev.javaharness.spi.HarnessException;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 输入框接口：@ 引用的工作区文件搜索，以及消息附件的上传、列表与内容读取。 */
@RestController
@RequestMapping("/api")
public class ComposerController {
    private final ComposerInputService inputs;
    private final GroupChatService groups;
    private final PromptOptimizationService optimization;

    public ComposerController(
            ComposerInputService inputs, GroupChatService groups, PromptOptimizationService optimization) {
        this.inputs = inputs;
        this.groups = groups;
        this.optimization = optimization;
    }

    /** 优化草稿只返回预览，不创建会话或任务；草稿可按所选工作区使用引用和记忆。 */
    @PostMapping("/composer/optimize")
    public PromptOptimizationService.Result optimize(@RequestBody PromptOptimizationService.Draft draft) {
        if (draft.sessionId() != null && !draft.sessionId().isBlank()) groups.requireOrdinary(draft.sessionId());
        return optimization.optimize(draft);
    }

    @GetMapping("/sessions/{id}/files")
    public List<WorkspaceFileSearch.Entry> files(@PathVariable String id, @RequestParam(defaultValue = "") String q) {
        groups.requireOrdinary(id);
        return inputs.search(id, q);
    }

    /** 首次发送前按工作区搜索文件，不持久化空会话。 */
    @GetMapping("/workspaces/{id}/files")
    public List<WorkspaceFileSearch.Entry> workspaceFiles(
            @PathVariable String id, @RequestParam(defaultValue = "") String q) {
        return inputs.searchInWorkspace(id, q);
    }

    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Attachment upload(@RequestParam MultipartFile file) {
        try {
            return inputs.upload(file.getOriginalFilename(), file.getBytes());
        } catch (IOException error) {
            throw new HarnessException("INVALID", "无法读取上传文件");
        }
    }

    @GetMapping("/sessions/{id}/attachments")
    public List<Attachment> attachments(@PathVariable String id) {
        return inputs.forSession(id);
    }

    /** 图片按原类型内联显示，文本按 UTF-8 纯文本返回；禁止浏览器嗅探类型。 */
    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id) {
        var stored = inputs.content(id);
        String type = stored.attachment().mediaType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type.equals("text/plain") ? "text/plain;charset=UTF-8" : type))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .body(stored.content());
    }
}
