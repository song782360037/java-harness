package dev.javaharness.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ParentProcessWatcherTest {

    @Test
    void runsCallbackOnlyAfterThePipeCloses() throws Exception {
        var output = new PipedOutputStream();
        var input = new PipedInputStream(output);
        var closed = new CountDownLatch(1);
        new ParentProcessWatcher(input, closed::countDown).start();

        output.write("ignored".getBytes());
        output.flush();
        assertThat(closed.await(200, TimeUnit.MILLISECONDS)).isFalse();

        output.close();
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void treatsReadFailureAsParentGone() throws Exception {
        var failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken pipe");
            }
        };
        var closed = new CountDownLatch(1);
        new ParentProcessWatcher(failing, closed::countDown).start();

        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
