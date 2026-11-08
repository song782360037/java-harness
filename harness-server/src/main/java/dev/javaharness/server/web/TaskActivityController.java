package dev.javaharness.server.web;

import dev.javaharness.server.service.TaskActivityService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 任务中心与桌面通知共用同一权威快照。 */
@RestController
@RequestMapping("/api/runs")
public class TaskActivityController {
    private final TaskActivityService activity;

    public TaskActivityController(TaskActivityService activity) {
        this.activity = activity;
    }

    @GetMapping("/activity")
    public ResponseEntity<TaskActivityService.Snapshot> activity() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activity.snapshot());
    }
}
