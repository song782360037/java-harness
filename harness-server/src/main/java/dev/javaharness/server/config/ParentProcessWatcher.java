package dev.javaharness.server.config;

import java.io.IOException;
import java.io.InputStream;

/**
 * 桌面模式下监听父进程（桌面壳）的标准输入管道：父进程退出或崩溃导致管道关闭时执行关闭回调，
 * 避免遗留后台 Java 进程占用端口和 H2 文件锁。
 */
public final class ParentProcessWatcher {
    private final InputStream input;
    private final Runnable onClosed;

    public ParentProcessWatcher(InputStream input, Runnable onClosed) {
        this.input = input;
        this.onClosed = onClosed;
    }

    /** 启动守护线程读取输入直到 EOF，随后执行关闭回调。 */
    public Thread start() {
        var thread = Thread.ofPlatform().daemon().name("parent-process-watcher").unstarted(this::awaitClose);
        thread.start();
        return thread;
    }

    private void awaitClose() {
        var buffer = new byte[256];
        try {
            while (input.read(buffer) >= 0) {
                // 父进程不会写入内容，读到的数据直接丢弃。
            }
        } catch (IOException ignored) {
            // 管道异常同样说明父进程已不可达。
        }
        onClosed.run();
    }
}
