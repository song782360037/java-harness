package dev.javaharness.server.web;

import dev.javaharness.server.service.DesktopUpdateGate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 仅桌面壳持有的随机令牌可以准备或撤销更新。 */
@RestController
@ConditionalOnProperty(name = "harness.desktop.update-token")
@RequestMapping("/api/desktop/update")
public class DesktopUpdateController {
    private final DesktopUpdateGate gate;
    private final String token;

    public DesktopUpdateController(DesktopUpdateGate gate, @Value("${harness.desktop.update-token}") String token) {
        this.gate = gate;
        this.token = token;
    }

    /** 检查活跃工作并暂时关闭提交入口。 */
    @PostMapping(value = "/prepare", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void prepare(@RequestHeader(value = "X-Harness-Desktop-Token", defaultValue = "") String supplied) {
        authorize(supplied);
        gate.prepare();
    }

    /** 下载后准备失败时恢复提交入口。 */
    @PostMapping(value = "/release", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@RequestHeader(value = "X-Harness-Desktop-Token", defaultValue = "") String supplied) {
        authorize(supplied);
        gate.release();
    }

    private void authorize(String supplied) {
        if (token.isBlank()
                || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
