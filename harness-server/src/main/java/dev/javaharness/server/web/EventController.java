package dev.javaharness.server.web;

import dev.javaharness.spi.HarnessException;
import dev.javaharness.spi.session.SessionStore;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话事件的 SSE 推送，可通过 {@code after} 或 {@code Last-Event-ID} 断点续传；并发连接数有上限。
 */
@RestController
@RequestMapping("/api/sessions")
public class EventController {
    private final SessionStore store;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(32);

    public EventController(SessionStore store) {
        this.store = store;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        long cursor = after;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                cursor = Long.parseLong(lastEventId);
            } catch (NumberFormatException error) {
                throw new HarnessException("INVALID", "无效的 Last-Event-ID");
            }
        }
        store.events(id, cursor, 1); // Validate before response headers are committed.
        if (!slots.tryAcquire()) throw new HarnessException("UNAVAILABLE", "实时连接过多，请稍后重试");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean done = new AtomicBoolean();
        emitter.onCompletion(() -> done.set(true));
        emitter.onTimeout(() -> done.set(true));
        emitter.onError(error -> done.set(true));
        final long initialCursor = cursor;
        try {
            streams.execute(() -> follow(id, initialCursor, emitter, done));
        } catch (RejectedExecutionException error) {
            slots.release();
            throw new HarnessException("UNAVAILABLE", "服务正在停止");
        }
        return emitter;
    }

    private void follow(String sessionId, long cursor, SseEmitter emitter, AtomicBoolean done) {
        long heartbeat = 0;
        long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        try {
            while (!done.get() && !Thread.currentThread().isInterrupted() && System.nanoTime() < expires) {
                // The database is also the replay queue. No history/live subscription gap.
                var batch = store.events(sessionId, cursor, 128);
                for (var event : batch) {
                    if (done.get()) return;
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.seq()))
                            .name("run-event")
                            .data(event));
                    cursor = event.seq();
                }
                if (System.nanoTime() - heartbeat > TimeUnit.SECONDS.toNanos(5)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    heartbeat = System.nanoTime();
                }
                if (batch.size() < 128) Thread.sleep(200);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException error) {
            // Disconnected clients reconnect from their last committed cursor.
        } catch (Exception error) {
            emitter.completeWithError(error);
        } finally {
            slots.release();
            emitter.complete();
        }
    }

    @PreDestroy
    public void close() {
        streams.shutdownNow();
    }
}
