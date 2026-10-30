package dev.javaharness.server.web;

import dev.javaharness.server.service.UsageStatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 使用统计接口：按最近天数汇总本项目与本机其他 agent 的 token 用量。 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {
    private final UsageStatisticsService usage;

    public UsageController(UsageStatisticsService usage) {
        this.usage = usage;
    }

    @GetMapping
    public UsageStatisticsService.Statistics statistics(@RequestParam(defaultValue = "7") int days) {
        return usage.statistics(days);
    }
}
