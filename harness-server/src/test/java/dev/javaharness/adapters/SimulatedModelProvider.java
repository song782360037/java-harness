package dev.javaharness.adapters;

import dev.javaharness.server.TestModelProvider;
import dev.javaharness.spi.ModelRequest;
import java.util.function.Consumer;

/** Test fixture only; never packaged in the application or selectable through configuration. */
public final class SimulatedModelProvider implements TestModelProvider {
    private final long chunkDelayMillis;

    public SimulatedModelProvider(long chunkDelayMillis) {
        this.chunkDelayMillis = chunkDelayMillis;
    }

    @Override
    public void stream(ModelRequest request, Cancellation cancellation, Consumer<String> onText) throws Exception {
        emit("已收到你的输入：\n\n" + request.messages().getLast().content() + "\n\n", cancellation, onText);
        if ("FAIL".equals(request.scenario())) throw new IllegalStateException("Simulated model failure");
        if ("SLOW".equals(request.scenario())) {
            while (true) {
                cancellation.check();
                Thread.sleep(100);
            }
        }
        emit("这是一段本地模拟输出，用于验证任务执行、流式展示和会话恢复。\n"
                + "当前尚未连接真实模型，也不会读取文件或执行命令。", cancellation, onText);
    }

    private void emit(String text, Cancellation cancellation, Consumer<String> onText) throws Exception {
        int[] points = text.codePoints().toArray();
        for (int offset = 0; offset < points.length; offset += 6) {
            cancellation.check();
            Thread.sleep(chunkDelayMillis);
            cancellation.check();
            onText.accept(new String(points, offset, Math.min(6, points.length - offset)));
        }
    }
}
