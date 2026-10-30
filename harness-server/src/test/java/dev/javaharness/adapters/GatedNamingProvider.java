package dev.javaharness.adapters;

import dev.javaharness.server.TestModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Test fixture only: instant answers for runs, a latch-gated title for naming requests. */
public final class GatedNamingProvider implements TestModelProvider {
    public static final String NAMING_PREFIX = "请用不超过 10 个字符";
    public static final String GENERATED_TITLE = "门控命名";
    /** 命名请求进入 stream 时置位，测试借此确认命名已卡在门内。 */
    public volatile CountDownLatch namingEntered = new CountDownLatch(1);
    /** 测试放行前命名请求一直等待，构造用户改名的时间窗口。 */
    public volatile CountDownLatch releaseNaming = new CountDownLatch(1);
    /** 命名请求完成（stream 返回）时置位。 */
    public volatile CountDownLatch namingCompleted = new CountDownLatch(1);
    public volatile int namingCalls;

    @Override
    public void stream(ModelRequest request, Cancellation cancellation, Consumer<String> onText) throws Exception {
        if (request.messages().getLast().content().startsWith(NAMING_PREFIX)) {
            namingCalls++;
            namingEntered.countDown();
            if (!releaseNaming.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Naming gate not released");
            namingCompleted.countDown();
            onText.accept(GENERATED_TITLE);
            return;
        }
        onText.accept("回答：" + request.messages().getLast().content());
    }

    /** 每个用例独立门控，避免互相放行。 */
    public void resetGate() {
        namingEntered = new CountDownLatch(1);
        releaseNaming = new CountDownLatch(1);
        namingCompleted = new CountDownLatch(1);
        namingCalls = 0;
    }
}
