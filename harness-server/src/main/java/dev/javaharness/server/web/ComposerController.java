package dev.javaharness.server.web;

import dev.javaharness.adapters.attachment.AttachmentStore.Attachment;
import dev.javaharness.adapters.workspace.WorkspaceBrowser;
import dev.javaharness.adapters.workspace.WorkspaceFileSearch;
import dev.javaharness.server.service.ComposerInputService;
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
    private final PromptOptimizationService optimization;

    public ComposerController(ComposerInputService inputs, PromptOptimizationService optimization) {
        this.inputs = inputs;
        this.optimization = optimization;
    }

    /** 优化草稿只返回预览，不创建会话或任务；草稿可按所选工作区使用引用和记忆。 */
    @PostMapping("/composer/optimize")
    public PromptOptimizationService.Result optimize(@RequestBody PromptOptimizationService.Draft draft) {
        return optimization.optimize(draft);
    }

    @GetMapping("/sessions/{id}/files")
    public List<WorkspaceFileSearch.Entry> files(@PathVariable String id, @RequestParam(defaultValue = "") String q) {
        return inputs.search(id, q);
    }

    /** 首次发送前按工作区搜索文件，不持久化空会话。 */
    @GetMapping("/workspaces/{id}/files")
    public List<WorkspaceFileSearch.Entry> workspaceFiles(
            @PathVariable String id, @RequestParam(defaultValue = "") String q) {
        return inputs.searchInWorkspace(id, q);
    }

    /** 浏览会话绑定工作区的目录树。 */
    @GetMapping("/sessions/{id}/tree")
    public WorkspaceBrowser.DirectoryView sessionTree(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int root,
            @RequestParam(defaultValue = "") String path) {
        return inputs.listDirectory(id, root, path);
    }

    /** 只读预览会话绑定工作区中的单个文件。 */
    @GetMapping("/sessions/{id}/file")
    public WorkspaceBrowser.FilePreview sessionFile(
            @PathVariable String id, @RequestParam(defaultValue = "1") int root, @RequestParam String path) {
        return inputs.previewFile(id, root, path);
    }

    /** 草稿态按工作区浏览目录树，不创建会话。 */
    @GetMapping("/workspaces/{id}/tree")
    public WorkspaceBrowser.DirectoryView workspaceTree(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int root,
            @RequestParam(defaultValue = "") String path) {
        return inputs.listWorkspaceDirectory(id, root, path);
    }

    /** 草稿态按工作区预览单个文件，不创建会话。 */
    @GetMapping("/workspaces/{id}/file")
    public WorkspaceBrowser.FilePreview workspaceFile(
            @PathVariable String id, @RequestParam(defaultValue = "1") int root, @RequestParam String path) {
        return inputs.previewWorkspaceFile(id, root, path);
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
