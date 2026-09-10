package dev.javaharness.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Text-only OpenAI-compatible Chat Completions using the core's frozen messages. */
public final class OpenAiChatCompletionProvider implements ModelProvider, AutoCloseable {
    private static final int EVENT_LIMIT = 65_536;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final String apiKey;
    private final ModelInfo info;

    public OpenAiChatCompletionProvider(URI baseUrl, String apiKey, String model,
                                       boolean apiKeyRequired, Duration connectTimeout) {
        boolean localHttp = "http".equals(baseUrl.getScheme())
                && baseUrl.getHost() != null
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost());
        if ((!"https".equals(baseUrl.getScheme()) && !localHttp) || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("Model base URL requires HTTPS (HTTP allowed only on loopback)");
        }
        String modelName = model == null ? "" : model.strip();
        if (modelName.length() > 128 || modelName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid model name");
        }
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        if (this.apiKey.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("Invalid API key format");
        }
        this.endpoint = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions");
        this.info = new ModelInfo("openai-compatible", modelName,
                !modelName.isEmpty() && (!apiKeyRequired || !this.apiKey.isEmpty()));
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public ModelInfo info() { return info; }

    @Override
    public void stream(ModelRequest request, Cancellation cancellation, Consumer<String> onText) throws Exception {
        if (!info.configured()) throw new ModelException("请检查 HARNESS_MODEL_NAME 和 HARNESS_MODEL_API_KEY 配置并重启服务");
        if (!request.scenario().equals("NORMAL")) throw new ModelException("真实模型不支持模拟场景");
        cancellation.check();
        var payload = Map.of("model", request.model(), "stream", true, "messages", request.messages());
        var builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (!apiKey.isEmpty()) builder.header("Authorization", "Bearer " + apiKey);
        var httpRequest = builder.build();
        var pending = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofPublisher());
        boolean claimed = false;
        try {
            var response = pending.get(); // Interruptible even while response headers are stalled.
            try (var body = new PublisherInputStream()) {
                response.body().subscribe(body);
                claimed = true;
                cancellation.check();
                if (response.statusCode() != 200) throw httpFailure(response.statusCode());
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.split(";", 2)[0].strip().equalsIgnoreCase("text/event-stream")) {
                    throw new ModelException("模型响应不是 SSE 数据流");
                }
                parse(new InputStreamReader(body, StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), cancellation, onText);
            }
        } catch (InterruptedException error) {
            // If headers won the race with interruption, dispose of their otherwise unclaimed body.
            if (!claimed) pending.thenAccept(response -> response.body().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription value) { value.cancel(); }
                @Override public void onNext(List<ByteBuffer> value) {}
                @Override public void onError(Throwable ignored) {}
                @Override public void onComplete() {}
            }));
            throw error;
        } catch (ModelException error) {
            throw error;
        } catch (IOException | ExecutionException error) {
            // Do not propagate upstream bodies, request headers, URLs or exception causes to logs/UI.
            throw new ModelException("模型连接失败或数据流损坏，请检查网络和服务配置");
        } finally {
            pending.cancel(true);
        }
    }

    private static ModelException httpFailure(int status) {
        return new ModelException(switch (status) {
            case 401, 403 -> "模型鉴权失败，请检查 API Key 和访问权限";
            case 402 -> "模型账户余额不足";
            case 429 -> "模型请求受限，请稍后手动重试";
            default -> "模型服务返回 HTTP " + status + "，请检查服务配置或稍后重试";
        });
    }

    private void parse(Reader reader, Cancellation cancellation, Consumer<String> onText) throws Exception {
        // Read bounded lines ourselves: BufferedReader.readLine would permit an unbounded upstream line.
        var input = new BufferedReader(reader);
        var line = new StringBuilder();
        var data = new StringBuilder();
        boolean stopped = false;
        boolean afterCr = false;
        int eventSize = 0;
        int ch;
        while ((ch = input.read()) != -1) {
            cancellation.check();
            if (afterCr && ch == '\n') { afterCr = false; continue; }
            afterCr = ch == '\r';
            if (++eventSize > EVENT_LIMIT) throw new ModelException("模型事件超过大小限制");
            if (ch != '\n' && ch != '\r') { line.append((char) ch); continue; }
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    String event = data.toString();
                    if (event.equals("[DONE]")) {
                        if (!stopped) throw new ModelException("模型流缺少正常结束原因");
                        return;
                    }
                    stopped = consume(event, stopped, onText);
                }
                data.setLength(0);
                eventSize = 0;
            } else if (line.toString().startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
            line.setLength(0);
        }
        throw new ModelException("模型数据流提前结束，已保留部分输出；请手动重试");
    }

    private boolean consume(String event, boolean stopped, Consumer<String> onText) throws Exception {
        JsonNode root = json.readTree(event);
        if (root == null || !root.isObject() || root.has("error")) throw new ModelException("模型返回错误或无效数据");
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) throw new ModelException("模型响应缺少 choices");
        if (choices.isEmpty()) return stopped; // Optional usage-only chunk.
        if (stopped || choices.size() != 1 || choices.get(0).path("index").asInt(-1) != 0) {
            throw new ModelException("模型返回非预期的输出分支");
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) throw new ModelException("模型响应缺少 delta");
        JsonNode tools = delta.get("tool_calls");
        if ((tools != null && !tools.isNull() && (!tools.isArray() || !tools.isEmpty()))
                || delta.hasNonNull("function_call")) {
            throw new ModelException("当前版本尚不支持模型工具调用");
        }
        if (delta.hasNonNull("refusal") && !delta.path("refusal").asText().isEmpty()) {
            throw new ModelException("模型拒绝回答此请求");
        }
        // reasoning_content is intentionally not emitted or persisted as the assistant's final answer.
        JsonNode content = delta.get("content");
        if (content != null && !content.isNull()) {
            if (!content.isTextual()) throw new ModelException("模型文本格式无效");
            String text = content.asText();
            for (int offset = 0; offset < text.length();) {
                int end = Math.min(offset + 4096, text.length());
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                onText.accept(text.substring(offset, end));
                offset = end;
            }
        }
        JsonNode finish = choice.get("finish_reason");
        if (finish == null || finish.isNull()) return false;
        if (finish.asText().equals("stop")) return true;
        throw new ModelException(switch (finish.asText()) {
            case "length" -> "模型达到输出长度限制，已保留部分输出";
            case "content_filter" -> "模型内容被过滤，已保留部分输出";
            case "tool_calls" -> "当前版本尚不支持模型工具调用";
            case "insufficient_system_resource" -> "模型服务资源不足，已保留部分输出";
            default -> "模型以非正常原因结束，已保留部分输出";
        });
    }

    @Override public void close() { client.shutdownNow(); }

    /** One requested batch at a time; queue waits are interruptible and close cancels the HTTP body. */
    private static final class PublisherInputStream extends InputStream implements Flow.Subscriber<List<ByteBuffer>> {
        private static final Object END = new Object();
        private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(2);
        private final CompletableFuture<Flow.Subscription> subscribed = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean closed;
        private Iterator<ByteBuffer> batch = Collections.emptyIterator();
        private ByteBuffer buffer = ByteBuffer.allocate(0);
        private boolean requested;

        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            if (closed) value.cancel();
            else { subscription = value; subscribed.complete(value); }
        }
        @Override public void onNext(List<ByteBuffer> value) { queue.add(value); }
        @Override public void onError(Throwable error) { queue.add(error); }
        @Override public void onComplete() { queue.add(END); }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            while (!buffer.hasRemaining()) {
                if (batch.hasNext()) { buffer = batch.next(); continue; }
                Object item;
                try {
                    var active = subscribed.get();
                    synchronized (this) {
                        if (closed) throw new IOException("Body closed");
                        if (!requested) { requested = true; active.request(1); }
                    }
                    item = queue.take();
                }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Body read interrupted");
                } catch (ExecutionException error) {
                    throw new IOException("Body subscription failed");
                }
                if (item == END) return -1;
                if (item instanceof Throwable) throw new IOException("Body failed");
                @SuppressWarnings("unchecked") var buffers = (List<ByteBuffer>) item;
                batch = buffers.iterator();
                requested = false;
            }
            int count = Math.min(length, buffer.remaining());
            buffer.get(bytes, offset, count);
            return count;
        }

        @Override public synchronized void close() {
            closed = true;
            if (subscription != null) subscription.cancel();
        }
    }
}
