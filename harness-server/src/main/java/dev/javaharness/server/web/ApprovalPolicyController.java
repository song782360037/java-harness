package dev.javaharness.server.web;

import dev.javaharness.adapters.approval.ApprovalPolicy;
import dev.javaharness.adapters.approval.ApprovalReviewer;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 读取与保存智能审批、完全访问模式使用的命令规则，以及智能审批的评审器设置。 */
@RestController
@RequestMapping("/api")
public class ApprovalPolicyController {
    private final ApprovalPolicy policy;
    private final ApprovalReviewer reviewer;

    public ApprovalPolicyController(ApprovalPolicy policy, ApprovalReviewer reviewer) {
        this.policy = policy;
        this.reviewer = reviewer;
    }

    /** Reviewer settings never return the Jev key, only whether one is saved. */
    @GetMapping("/approval-reviewer")
    public ApprovalReviewer.Settings reviewer() {
        return reviewer.settings();
    }

    @PostMapping(value = "/approval-reviewer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApprovalReviewer.Settings saveReviewer(@RequestBody ApprovalReviewer.SaveSettings request) {
        return reviewer.save(request);
    }

    public record SaveRules(long revision, List<String> deny, List<String> ask, List<String> allow) {}

    @GetMapping("/approval-policy")
    public ApprovalPolicy.Rules rules() {
        return policy.rules();
    }

    @PostMapping(value = "/approval-policy", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApprovalPolicy.Rules save(@RequestBody SaveRules request) {
        return policy.save(request.revision(), request.deny(), request.ask(), request.allow());
    }
}
