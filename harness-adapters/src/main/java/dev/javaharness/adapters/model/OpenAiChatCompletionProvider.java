package dev.javaharness.adapters.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javaharness.spi.ModelException;
import dev.javaharness.spi.model.ModelInfo;
import dev.javaharness.spi.model.ModelMessage;
import dev.javaharness.spi.model.ModelProvider;
import dev.javaharness.spi.model.ModelRequest;
import dev.javaharness.spi.model.ModelTurn;
import dev.javaharness.spi.model.ModelUsage;
import dev.javaharness.spi.tool.ToolCall;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** OpenAI-compatible streaming Chat Completions, including bounded function-call deltas and safe retries. */
public final class OpenAiChatCompletionProvider implements ModelProvider, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(OpenAiChatCompletionProvider.class.getName());
    private static final int EVENT_LIMIT = 65_536;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMillis(500);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(15);
    private static final Set<String> NON_RETRYABLE_SSE_ERRORS = Set.of(
            "invalid_request_error",
            "authentication_error",
            "permission_error",
            "not_found_error",
            "insufficient_quota",
            "billing_hard_limit_reached",
            "context_length_exceeded",
            "model_not_found",
            "invalid_api_key");

    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;
    private final String apiKey;
    private final ModelInfo info;
    private final int maxRetries;
    private final Duration retryBaseDelay;
    private volatile boolean usageOptions = true;

    public OpenAiChatCompletionProvider(
            URI baseUrl, String apiKey, String model, boolean apiKeyRequired, Duration connectTimeout) {
        this(baseUrl, apiKey, model, apiKeyRequired, connectTimeout, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BASE_DELAY);
    }

    public OpenAiChatCompletionProvider(
            URI baseUrl,
            String apiKey,
            String model,
            boolean apiKeyRequired,
            Duration connectTimeout,
            int maxRetries,
            Duration retryBaseDelay) {
        boolean localHttp = "http".equals(baseUrl.getScheme())
                && baseUrl.getHost() != null
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost());
        if ((!"https".equals(baseUrl.getScheme()) && !localHttp)
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
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
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("Invalid maxRetries");
        }
        if (retryBaseDelay == null || retryBaseDelay.isNegative()) {
            throw new IllegalArgumentException("Invalid retryBaseDelay");
        }
        this.endpoint = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions");
        this.info = new ModelInfo(
                "openai-compatible", modelName, !modelName.isEmpty() && (!apiKeyRequired || !this.apiKey.isEmpty()));
        this.maxRetries = maxRetries;
        this.retryBaseDelay = retryBaseDelay;
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ModelInfo info() {
        return info;
    }

    @Override
    public void stream(ModelRequest request, Cancellation cancellation, Consumer<String> onText) throws Exception {
        complete(request, cancellation, onText);
    }

    @Override
    public ModelTurn complete(ModelRequest request, Cancellation cancellation, Consumer<String> onText)
            throws Exception {
        return complete(request, cancellation, onText, ignored -> {});
    }

    @Override
    public ModelTurn complete(
            ModelRequest request, Cancellation cancellation, Consumer<String> onText, Consumer<String> onReasoning)
            throws Exception {
        if (!info.configured()) throw new ModelException("请在页面的模型配置中设置模型名称和 API Key");
        if (!request.scenario().equals("NORMAL")) throw new ModelException("真实模型不支持模拟场景");
        cancellation.check();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", request.model());
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));
        if (!request.reasoningEffort().isEmpty()) payload.put("reasoning_effort", request.reasoningEffort());
        var messages = new ArrayList<Map<String, Object>>();
        if (!request.instructions().isBlank())
            messages.add(Map.of("role", "system", "content", request.instructions()));
        messages.addAll(request.messages().stream()
                .map(OpenAiChatCompletionProvider::wireMessage)
                .toList());
        payload.put("messages", messages);
        if (!request.tools().isEmpty())
            payload.put(
                    "tools",
                    request.tools().stream()
                            .map(tool -> Map.of(
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            tool.name(),
                                            "description",
                                            tool.description(),
                                            "parameters",
                                            tool.parameters())))
                            .toList());
        if (request.finalAnswerOnly()) payload.put("tool_choice", "none");

        int maxAttempts = maxRetries + 1;
        boolean emittedReasoningBefore = false;
        for (int attempt = 1; ; attempt++) {
            cancellation.check();
            if (!usageOptions) payload.remove("stream_options");
            var tracker = new AttemptTracker(onText, onReasoning, emittedReasoningBefore);
            try {
                return send(request, payload, cancellation, tracker, attempt, maxAttempts);
            } catch (UsageOptionsRejected rejected) {
                // Usage is display-only: services that reject stream_options keep working without it.
                LOG.log(
                        System.Logger.Level.INFO,
                        "Model request rejected stream_options with HTTP 400; retrying without stream_options "
                                + "(runId={0}, step={1}, model={2}, host={3})",
                        request.runId(),
                        request.step(),
                        request.model(),
                        endpoint.getHost());
                usageOptions = false;
                payload.remove("stream_options");
                attempt--;
            } catch (RetryableFailure retryable) {
                emittedReasoningBefore |= tracker.emittedReasoning;
                if (attempt >= maxAttempts || tracker.emittedText) {
                    throw new ModelException(retryable.userMessage);
                }
                Duration delay = retryDelay(attempt, retryable.retryAfter);
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Retrying model request after transient failure "
                                + "(phase={0}, runId={1}, step={2}, model={3}, host={4}, attempt={5}/{6}, "
                                + "delayMs={7}, detail={8})",
                        retryable.phase,
                        request.runId(),
                        request.step(),
                        request.model(),
                        endpoint.getHost(),
                        attempt,
                        maxAttempts,
                        delay.toMillis(),
                        retryable.detail);
                sleep(delay, cancellation);
            }
        }
    }

    /** Raised before any output when a request carrying stream_options gets HTTP 400. */
    private static final class UsageOptionsRejected extends Exception {
        UsageOptionsRejected() {
            super(null, null, false, false);
        }
    }

    /** Transient upstream failure eligible for retry when no answer text has been emitted yet. */
    private static final class RetryableFailure extends Exception {
        final String phase;
        final String detail;
        final Duration retryAfter;
        final String userMessage;

        RetryableFailure(String phase, String detail, Duration retryAfter, String userMessage) {
            super(null, null, false, false);
            this.phase = phase;
            this.detail = detail;
            this.retryAfter = retryAfter;
            this.userMessage = userMessage;
        }
    }

    /** Wraps the underlying HTTP body failure cause for sanitized logging without exposing it to the UI. */
    private static final class BodyReadException extends IOException {
        BodyReadException(Throwable cause) {
            super("Body failed", cause);
        }
    }

    /** Tracks emitted output and chunk counts for a single attempt. */
    private static final class AttemptTracker {
        private final Consumer<String> onText;
        private final Consumer<String> onReasoning;
        private final boolean separateReasoning;
        boolean emittedText;
        boolean emittedReasoning;
        int textChars;
        int reasoningChars;
        int chunks;

        AttemptTracker(Consumer<String> onText, Consumer<String> onReasoning, boolean separateReasoning) {
            this.onText = onText;
            this.onReasoning = onReasoning;
            this.separateReasoning = separateReasoning;
        }

        void text(String piece) {
            if (!piece.isEmpty()) {
                emittedText = true;
                textChars += piece.length();
            }
            onText.accept(piece);
        }

        void reasoning(String piece) {
            if (!piece.isEmpty()) {
                if (!emittedReasoning && separateReasoning) onReasoning.accept("\n\n");
                emittedReasoning = true;
                reasoningChars += piece.length();
            }
            onReasoning.accept(piece);
        }
    }

    private ModelTurn send(
            ModelRequest request,
            Map<String, Object> payload,
            Cancellation cancellation,
            AttemptTracker tracker,
            int attempt,
            int maxAttempts)
            throws Exception {
        String encoded;
        try {
            encoded = json.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Model request serialization failed (phase=request-encode, runId={0}, step={1}, model={2}, error={3})",
                    request.runId(),
                    request.step(),
                    request.model(),
                    error.getClass().getSimpleName());
            throw new ModelException("请求编码失败，请检查输入内容");
        }
        var builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(encoded));
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
                int status = response.statusCode();
                if (status == 400 && payload.containsKey("stream_options")) throw new UsageOptionsRejected();
                if (status == 400 && hasImages(payload)) {
                    logUpstream(request, "http-status", attempt, maxAttempts, "status=400,hasImages=true", tracker);
                    throw new ModelException("模型服务返回 HTTP 400：请求包含图片，可能是当前模型不支持图片输入，或图片与上下文超出服务限制；请换用支持视觉的模型或减少图片");
                }
                if (status != 200) {
                    Duration retryAfter = parseRetryAfter(response);
                    String detail =
                            "status=" + status + (retryAfter == null ? "" : ",retryAfterMs=" + retryAfter.toMillis());
                    logUpstream(request, "http-status", attempt, maxAttempts, detail, tracker);
                    if (isRetryableHttpStatus(status)) {
                        throw new RetryableFailure("http-status", detail, retryAfter, httpFailureMessage(status));
                    }
                    throw new ModelException(httpFailureMessage(status));
                }
                String contentType =
                        response.headers().firstValue("Content-Type").orElse("");
                String mediaType = contentType.split(";", 2)[0].strip();
                if (!mediaType.equalsIgnoreCase("text/event-stream")) {
                    logParse(
                            request,
                            "content-type",
                            attempt,
                            maxAttempts,
                            "contentType=" + safeToken(mediaType, 64),
                            tracker);
                    throw new ModelException("响应解析失败：上游响应不是 SSE 数据流");
                }
                return parse(
                        request,
                        new InputStreamReader(
                                body,
                                StandardCharsets.UTF_8
                                        .newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT)),
                        cancellation,
                        tracker,
                        attempt,
                        maxAttempts);
            }
        } catch (InterruptedException error) {
            disposeIfUnclaimed(claimed, pending);
            throw error;
        } catch (InterruptedIOException error) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Model stream interrupted");
            String cause = causeSummary(error);
            logUpstream(request, "stream-io", attempt, maxAttempts, "cause=" + cause, tracker);
            throw new RetryableFailure(
                    "stream-io",
                    "cause=" + cause,
                    null,
                    withPartial("上游模型数据流读取中断", tracker.emittedText) + "；请检查网络或稍后重试");
        } catch (ModelException | UsageOptionsRejected | RetryableFailure error) {
            throw error;
        } catch (CharacterCodingException error) {
            logParse(
                    request,
                    "utf8-decode",
                    attempt,
                    maxAttempts,
                    "error=" + error.getClass().getSimpleName(),
                    tracker);
            throw new ModelException(withPartial("响应解析失败：模型数据流包含无效 UTF-8 字节", tracker.emittedText));
        } catch (BodyReadException error) {
            String cause = causeSummary(error.getCause());
            logUpstream(request, "stream-read", attempt, maxAttempts, "cause=" + cause, tracker);
            throw new RetryableFailure(
                    "stream-read",
                    "cause=" + cause,
                    null,
                    withPartial("上游模型数据流中断", tracker.emittedText) + "；请检查网络或稍后重试");
        } catch (ExecutionException error) {
            String cause = causeSummary(error.getCause());
            logUpstream(request, "connect", attempt, maxAttempts, "cause=" + cause, tracker);
            throw new RetryableFailure("connect", "cause=" + cause, null, "上游模型连接失败，请检查网络和服务配置");
        } catch (IOException error) {
            // Do not propagate upstream bodies, request headers, URLs or exception causes to logs/UI.
            String cause = causeSummary(error);
            logUpstream(request, "stream-io", attempt, maxAttempts, "cause=" + cause, tracker);
            throw new RetryableFailure(
                    "stream-io",
                    "cause=" + cause,
                    null,
                    withPartial("上游模型连接或数据流读取失败", tracker.emittedText) + "；请检查网络和服务配置");
        } finally {
            pending.cancel(true);
        }
    }

    private static void disposeIfUnclaimed(
            boolean claimed, CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> pending) {
        // If headers won the race with interruption, dispose of their otherwise unclaimed body.
        if (!claimed)
            pending.thenAccept(response -> response.body().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription value) {
                    value.cancel();
                }

                @Override
                public void onNext(List<ByteBuffer> value) {}

                @Override
                public void onError(Throwable ignored) {}

                @Override
                public void onComplete() {}
            }));
    }

    private static boolean isRetryableHttpStatus(int status) {
        return status == 408
                || status == 429
                || status == 500
                || status == 502
                || status == 503
                || status == 504
                || (status >= 520 && status <= 529);
    }

    private static Duration parseRetryAfter(HttpResponse<?> response) {
        String raw = response.headers().firstValue("Retry-After").orElse("").strip();
        if (raw.isEmpty()) return null;
        try {
            long seconds = Long.parseLong(raw);
            return seconds < 0 ? null : Duration.ofSeconds(Math.min(seconds, MAX_RETRY_AFTER.toSeconds()));
        } catch (NumberFormatException ignored) {
            try {
                Instant until = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration delta = Duration.between(Instant.now(), until);
                if (delta.isNegative()) return Duration.ZERO;
                return delta.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : delta;
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    private Duration retryDelay(int attempt, Duration retryAfter) {
        if (retryAfter != null && !retryAfter.isNegative()) {
            return retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
        }
        long baseMillis = retryBaseDelay.toMillis();
        if (baseMillis <= 0) return Duration.ZERO;
        long shift = Math.min(attempt - 1, 10);
        long expMillis = Math.min(MAX_RETRY_DELAY.toMillis(), baseMillis * (1L << shift));
        long jitter = expMillis >= 4 ? ThreadLocalRandom.current().nextLong(expMillis / 4 + 1) : 0;
        return Duration.ofMillis(Math.min(MAX_RETRY_DELAY.toMillis(), expMillis + jitter));
    }

    private static void sleep(Duration delay, Cancellation cancellation) throws InterruptedException {
        cancellation.check();
        long remainingNanos = delay.toNanos();
        if (remainingNanos <= 0) return;
        long deadline = System.nanoTime() + remainingNanos;
        while (true) {
            cancellation.check();
            long leftNanos = deadline - System.nanoTime();
            if (leftNanos <= 0) return;
            long sliceMillis = Math.min(100L, Math.max(1L, leftNanos / 1_000_000L));
            Thread.sleep(sliceMillis);
        }
    }

    /** Multimodal user messages are the only ones whose content is a part list. */
    private static boolean hasImages(Map<String, Object> payload) {
        return payload.get("messages") instanceof List<?> messages
                && messages.stream()
                        .anyMatch(message -> message instanceof Map<?, ?> map && map.get("content") instanceof List<?>);
    }

    private static String httpFailureMessage(int status) {
        return switch (status) {
            case 401, 403 -> "上游模型鉴权失败，请检查 API Key 和访问权限";
            case 402 -> "上游模型账户余额不足";
            case 408 -> "上游模型请求超时（HTTP 408），请稍后重试";
            case 429 -> "上游模型请求受限（HTTP 429），请稍后重试";
            case 500, 502, 503, 504 -> "上游模型服务异常（HTTP " + status + "），请稍后重试";
            default -> "上游模型服务返回 HTTP " + status + "，请检查服务配置或稍后重试";
        };
    }

    private static String withPartial(String base, boolean emittedText) {
        return emittedText ? base + "，已保留部分输出" : base;
    }

    private static Map<String, Object> wireMessage(ModelMessage message) {
        var value = new LinkedHashMap<String, Object>();
        value.put("role", message.role());
        if (message.images().isEmpty()) value.put("content", message.content());
        else {
            var parts = new ArrayList<Map<String, Object>>();
            parts.add(Map.of("type", "text", "text", message.content()));
            for (var image : message.images())
                parts.add(Map.of(
                        "type",
                        "image_url",
                        "image_url",
                        Map.of("url", "data:" + image.mediaType() + ";base64," + image.data())));
            value.put("content", parts);
        }
        if (!message.toolCalls().isEmpty())
            value.put(
                    "tool_calls",
                    message.toolCalls().stream()
                            .map(call -> Map.of(
                                    "id",
                                    call.id(),
                                    "type",
                                    "function",
                                    "function",
                                    Map.of("name", call.name(), "arguments", call.arguments())))
                            .toList());
        if (message.role().equals("tool")) value.put("tool_call_id", message.toolCallId());
        return value;
    }

    private static final class CallDelta {
        final StringBuilder id = new StringBuilder(), name = new StringBuilder(), arguments = new StringBuilder();
    }

    private static final class TurnState {
        final StringBuilder text = new StringBuilder();
        final TreeMap<Integer, CallDelta> calls = new TreeMap<>();
        ModelUsage usage;
        boolean timed;
        long firstAt, lastAt;

        ModelTurn result() throws ModelException {
            var completed = new ArrayList<ToolCall>();
            var ids = new HashSet<String>();
            for (var entry : calls.entrySet()) {
                CallDelta call = entry.getValue();
                try {
                    var value = new ToolCall(call.id.toString(), call.name.toString(), call.arguments.toString());
                    if (!ids.add(value.id())) throw new IllegalArgumentException();
                    completed.add(value);
                } catch (IllegalArgumentException error) {
                    throw new ModelException("响应解析失败：模型工具调用格式无效");
                }
            }
            var reported = usage == null
                    ? null
                    : new ModelUsage(
                            usage.inputTokens(),
                            usage.outputTokens(),
                            usage.cachedTokens(),
                            timed ? (lastAt - firstAt) / 1_000_000 : 0);
            return new ModelTurn(text.toString(), completed, reported);
        }
    }

    private ModelTurn parse(
            ModelRequest request,
            Reader reader,
            Cancellation cancellation,
            AttemptTracker tracker,
            int attempt,
            int maxAttempts)
            throws Exception {
        // Read bounded lines ourselves: BufferedReader.readLine would permit an unbounded upstream line.
        var input = new BufferedReader(reader);
        var line = new StringBuilder();
        var data = new StringBuilder();
        boolean stopped = false;
        var state = new TurnState();
        boolean afterCr = false;
        int eventSize = 0;
        int ch;
        while ((ch = input.read()) != -1) {
            cancellation.check();
            if (afterCr && ch == '\n') {
                afterCr = false;
                continue;
            }
            afterCr = ch == '\r';
            if (++eventSize > EVENT_LIMIT) {
                logParse(request, "event-limit", attempt, maxAttempts, "eventSize>" + EVENT_LIMIT, tracker);
                throw new ModelException(withPartial("响应解析失败：模型事件超过大小限制", tracker.emittedText));
            }
            if (ch != '\n' && ch != '\r') {
                line.append((char) ch);
                continue;
            }
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    String event = data.toString();
                    if (event.strip().equals("[DONE]")) {
                        if (!stopped) {
                            logParse(request, "missing-stop", attempt, maxAttempts, "doneWithoutFinishReason", tracker);
                            throw new ModelException(withPartial("响应解析失败：模型流缺少正常结束原因", tracker.emittedText));
                        }
                        return state.result();
                    }
                    stopped = consume(request, event, stopped, state, tracker, attempt, maxAttempts);
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
        String detail = "stopped=" + stopped + ",toolCalls=" + state.calls.size();
        logUpstream(request, "stream-eof", attempt, maxAttempts, detail, tracker);
        if (!tracker.emittedText && state.calls.isEmpty() && !stopped) {
            throw new RetryableFailure("stream-eof", detail, null, "上游模型数据流提前结束，请稍后重试");
        }
        throw new ModelException(withPartial("上游模型数据流提前结束", tracker.emittedText) + "；请手动重试");
    }

    private boolean consume(
            ModelRequest request,
            String event,
            boolean stopped,
            TurnState state,
            AttemptTracker tracker,
            int attempt,
            int maxAttempts)
            throws Exception {
        JsonNode root;
        try {
            root = json.readTree(event);
        } catch (JsonProcessingException error) {
            logParse(
                    request,
                    "json-parse",
                    attempt,
                    maxAttempts,
                    "eventLen=" + event.length() + ",error=" + error.getClass().getSimpleName(),
                    tracker);
            throw new ModelException(withPartial("响应解析失败：SSE 事件不是合法 JSON", tracker.emittedText));
        }
        tracker.chunks++;
        if (root == null || !root.isObject()) {
            String nodeType = root == null ? "NULL" : root.getNodeType().name();
            logParse(
                    request,
                    "sse-schema",
                    attempt,
                    maxAttempts,
                    "rootType=" + nodeType + ",eventLen=" + event.length(),
                    tracker);
            throw new ModelException(withPartial("响应解析失败：SSE 事件不是 JSON 对象", tracker.emittedText));
        }
        if (root.hasNonNull("error")) {
            JsonNode errorNode = root.get("error");
            String errorType = safeToken(errorNode.path("type").asText(""), 64);
            String errorCode = safeToken(errorNode.path("code").asText(""), 64);
            boolean retryable = isRetryableSseError(errorType, errorCode);
            String detail = "errorType=" + errorType + ",errorCode=" + errorCode + ",retryable=" + retryable;
            logUpstream(request, "sse-error", attempt, maxAttempts, detail, tracker);
            String message = withPartial("上游模型在响应流中返回错误", tracker.emittedText) + "；请稍后重试";
            if (retryable) {
                throw new RetryableFailure("sse-error", detail, null, message);
            }
            throw new ModelException(message);
        }
        usage(root.get("usage"), state);
        JsonNode choices = root.path("choices");
        if (choices.isMissingNode() && root.path("usage").isObject()) return stopped; // Usage-only, no choices.
        if (!choices.isArray()) {
            logParse(request, "sse-schema", attempt, maxAttempts, "missingChoices,keys=" + keysSummary(root), tracker);
            throw new ModelException(withPartial("响应解析失败：模型响应缺少 choices", tracker.emittedText));
        }
        if (choices.isEmpty()) return stopped; // Optional usage-only chunk.
        long now = System.nanoTime();
        if (!state.timed) state.firstAt = now;
        state.timed = true;
        state.lastAt = now;
        if (choices.size() != 1 || choices.get(0).path("index").asInt(-1) != 0) {
            logParse(
                    request,
                    "sse-schema",
                    attempt,
                    maxAttempts,
                    "choicesSize=" + choices.size() + ",index="
                            + choices.get(0).path("index").asInt(-1),
                    tracker);
            throw new ModelException(withPartial("响应解析失败：模型返回非预期的输出分支", tracker.emittedText));
        }
        JsonNode choice = choices.get(0);
        if (stopped) {
            if (isBenignPostStopChoice(choice)) return true;
            logParse(
                    request,
                    "sse-schema",
                    attempt,
                    maxAttempts,
                    "postStopChoice,deltaKeys=" + keysSummary(choice.path("delta")),
                    tracker);
            throw new ModelException(withPartial("响应解析失败：模型在结束后继续返回输出", tracker.emittedText));
        }
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) {
            logParse(
                    request,
                    "sse-schema",
                    attempt,
                    maxAttempts,
                    "missingDelta,choiceKeys=" + keysSummary(choice),
                    tracker);
            throw new ModelException(withPartial("响应解析失败：模型响应缺少 delta", tracker.emittedText));
        }
        JsonNode tools = delta.get("tool_calls");
        if (delta.hasNonNull("function_call")) {
            logParse(request, "sse-schema", attempt, maxAttempts, "legacyFunctionCall", tracker);
            throw new ModelException("响应解析失败：请使用标准 tools/tool_calls 协议");
        }
        if (tools != null && !tools.isNull()) {
            if (!tools.isArray()) {
                logParse(request, "tool-call", attempt, maxAttempts, "toolCallsNotArray", tracker);
                throw new ModelException("响应解析失败：模型 tool_calls 格式无效，必须是数组");
            }
            if (tools.size() > ToolCall.MAX_BATCH_SIZE) {
                logParse(request, "tool-call", attempt, maxAttempts, "toolBatchSize=" + tools.size(), tracker);
                throw new ModelException("单步模型返回的工具调用超过 " + ToolCall.MAX_BATCH_SIZE + " 个解析上限");
            }
            for (JsonNode item : tools) {
                JsonNode index = item.path("index");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.asInt() < 0) {
                    logParse(request, "tool-call", attempt, maxAttempts, "invalidToolIndex", tracker);
                    throw new ModelException("响应解析失败：模型工具调用 index 无效，必须提供非负整数索引");
                }
                if (item.hasNonNull("type") && !item.path("type").asText().equals("function")) {
                    logParse(
                            request,
                            "tool-call",
                            attempt,
                            maxAttempts,
                            "invalidToolType=" + safeToken(item.path("type").asText(""), 32),
                            tracker);
                    throw new ModelException("响应解析失败：模型工具调用 type 无效，仅支持 function 类型");
                }
                // The index identifies a streamed call, not how many tools may execute.
                if (!state.calls.containsKey(index.asInt()) && state.calls.size() >= ToolCall.MAX_BATCH_SIZE) {
                    logParse(
                            request,
                            "tool-call",
                            attempt,
                            maxAttempts,
                            "distinctTools>" + ToolCall.MAX_BATCH_SIZE,
                            tracker);
                    throw new ModelException("单步模型返回的工具调用超过 " + ToolCall.MAX_BATCH_SIZE + " 个解析上限");
                }
                var call = state.calls.computeIfAbsent(index.asInt(), ignored -> new CallDelta());
                append(call.id, item.get("id"), 128);
                JsonNode function = item.get("function");
                if (function != null && !function.isNull()) {
                    if (!function.isObject()) {
                        logParse(request, "tool-call", attempt, maxAttempts, "functionNotObject", tracker);
                        throw new ModelException("响应解析失败：工具函数格式无效");
                    }
                    append(call.name, function.get("name"), 64);
                    append(call.arguments, function.get("arguments"), 16384);
                }
            }
        }
        if (delta.hasNonNull("refusal") && !delta.path("refusal").asText().isEmpty()) {
            logUpstream(request, "refusal", attempt, maxAttempts, "modelRefusal", tracker);
            throw new ModelException("模型拒绝回答此请求");
        }
        // Reasoning is shown in the timeline only; it is never part of the answer or resent to the model.
        JsonNode reasoning =
                delta.hasNonNull("reasoning_content") ? delta.get("reasoning_content") : delta.get("reasoning");
        if (reasoning != null && reasoning.isTextual()) emit(reasoning.asText(), tracker::reasoning);
        JsonNode content = delta.get("content");
        if (content != null && !content.isNull()) {
            if (!content.isTextual()) {
                logParse(
                        request,
                        "sse-schema",
                        attempt,
                        maxAttempts,
                        "contentType=" + content.getNodeType().name(),
                        tracker);
                throw new ModelException(withPartial("响应解析失败：模型文本格式无效", tracker.emittedText));
            }
            String text = content.asText();
            if (state.text.length() + text.length() > 100_000) {
                logParse(request, "output-limit", attempt, maxAttempts, "textLen>100000", tracker);
                throw new ModelException("单步模型输出超过字符限制");
            }
            state.text.append(text);
            emit(text, tracker::text);
        }
        JsonNode finish = choice.get("finish_reason");
        if (finish == null
                || finish.isNull()
                || (finish.isTextual() && finish.asText().isEmpty())) return false;
        String reason = finish.asText();
        if (reason.equals("stop") && state.calls.isEmpty()) return true;
        if (reason.equals("tool_calls") && !state.calls.isEmpty()) {
            state.result();
            return true;
        }
        logUpstream(
                request,
                "finish-reason",
                attempt,
                maxAttempts,
                "finishReason=" + safeToken(reason, 64) + ",toolCalls=" + state.calls.size(),
                tracker);
        throw new ModelException(
                switch (reason) {
                    case "length" -> "模型达到输出长度限制，已保留部分输出";
                    case "content_filter" -> "模型内容被过滤，已保留部分输出";
                    case "tool_calls" -> "响应解析失败：模型结束原因与工具调用不匹配";
                    case "stop" -> "响应解析失败：模型结束原因与工具调用不匹配";
                    case "insufficient_system_resource" -> "上游模型服务资源不足，已保留部分输出";
                    default -> "上游模型以非正常原因结束，已保留部分输出";
                });
    }

    /** Some gateways send a trailing usage/empty-delta choice chunk after finish_reason. */
    private static boolean isBenignPostStopChoice(JsonNode choice) {
        JsonNode finish = choice.get("finish_reason");
        if (finish != null && !finish.isNull()) {
            String reason = finish.asText("");
            if (!reason.isEmpty() && !reason.equals("stop") && !reason.equals("tool_calls")) return false;
        }
        JsonNode delta = choice.get("delta");
        if (delta == null || delta.isNull()) return true;
        if (!delta.isObject()) return false;
        if (delta.hasNonNull("content") && !delta.path("content").asText("").isEmpty()) return false;
        if (delta.hasNonNull("reasoning_content")
                && !delta.path("reasoning_content").asText("").isEmpty()) return false;
        if (delta.hasNonNull("reasoning") && !delta.path("reasoning").asText("").isEmpty()) return false;
        if (delta.hasNonNull("refusal") && !delta.path("refusal").asText("").isEmpty()) return false;
        if (delta.hasNonNull("function_call")) return false;
        JsonNode tools = delta.get("tool_calls");
        return tools == null || tools.isNull() || (tools.isArray() && tools.isEmpty());
    }

    private static boolean isRetryableSseError(String errorType, String errorCode) {
        String normalizedType = errorType.toLowerCase(Locale.ROOT);
        String normalizedCode = errorCode.toLowerCase(Locale.ROOT);
        return !NON_RETRYABLE_SSE_ERRORS.contains(normalizedType) && !NON_RETRYABLE_SSE_ERRORS.contains(normalizedCode);
    }

    private void logUpstream(
            ModelRequest request, String phase, int attempt, int maxAttempts, String detail, AttemptTracker tracker) {
        LOG.log(
                System.Logger.Level.WARNING,
                "Upstream model call failed (kind=upstream, phase={0}, runId={1}, step={2}, model={3}, host={4}, "
                        + "attempt={5}/{6}, chunks={7}, textChars={8}, reasoningChars={9}, detail={10})",
                phase,
                request.runId(),
                request.step(),
                request.model(),
                endpoint.getHost(),
                attempt,
                maxAttempts,
                tracker.chunks,
                tracker.textChars,
                tracker.reasoningChars,
                detail);
    }

    private void logParse(
            ModelRequest request, String phase, int attempt, int maxAttempts, String detail, AttemptTracker tracker) {
        LOG.log(
                System.Logger.Level.WARNING,
                "Model response parsing failed (kind=parse, phase={0}, runId={1}, step={2}, model={3}, host={4}, "
                        + "attempt={5}/{6}, chunks={7}, textChars={8}, reasoningChars={9}, detail={10})",
                phase,
                request.runId(),
                request.step(),
                request.model(),
                endpoint.getHost(),
                attempt,
                maxAttempts,
                tracker.chunks,
                tracker.textChars,
                tracker.reasoningChars,
                detail);
    }

    private static String causeSummary(Throwable error) {
        if (error == null) return "unknown";
        String top = error.getClass().getSimpleName();
        Throwable root = error;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 8) root = root.getCause();
        String leaf = root.getClass().getSimpleName();
        return top.equals(leaf) ? top : top + ":" + leaf;
    }

    private static String safeToken(String value, int maxLen) {
        if (value == null || value.isEmpty()) return "none";
        var cleaned = new StringBuilder();
        for (int i = 0; i < value.length() && cleaned.length() < maxLen; i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-'
                    || c == '.'
                    || c == '/'
                    || c == ':') {
                cleaned.append(c);
            }
        }
        return cleaned.isEmpty() ? "redacted" : cleaned.toString();
    }

    private static String keysSummary(JsonNode node) {
        if (node == null || !node.isObject()) return "none";
        var names = new ArrayList<String>();
        var fields = node.fieldNames();
        while (fields.hasNext() && names.size() < 8) names.add(safeToken(fields.next(), 32));
        return names.isEmpty() ? "empty" : String.join("|", names);
    }

    /** Usage is display-only: malformed or partial reports are ignored rather than failing the turn. */
    private static void usage(JsonNode usage, TurnState state) {
        if (usage == null || !usage.isObject()) return;
        int input = count(usage.get("prompt_tokens")), output = count(usage.get("completion_tokens"));
        if (input < 0 || output < 0) return;
        // OpenAI reports prompt_tokens_details.cached_tokens; DeepSeek reports prompt_cache_hit_tokens.
        int cached = count(usage.path("prompt_tokens_details").get("cached_tokens"));
        if (cached < 0) cached = count(usage.get("prompt_cache_hit_tokens"));
        state.usage = new ModelUsage(input, output, Math.min(Math.max(cached, 0), input), 0);
    }

    private static int count(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0
                ? value.asInt()
                : -1;
    }

    /** Split into store-sized chunks without separating a surrogate pair. */
    private static void emit(String text, Consumer<String> target) {
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(offset + 4096, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            target.accept(text.substring(offset, end));
            offset = end;
        }
    }

    private static void append(StringBuilder target, JsonNode value, int limit) throws ModelException {
        if (value == null || value.isNull()) return;
        if (!value.isTextual() || target.length() + value.asText().length() > limit)
            throw new ModelException("响应解析失败：工具调用字段无效或超过大小限制");
        target.append(value.asText());
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

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

        @Override
        public synchronized void onSubscribe(Flow.Subscription value) {
            if (closed) value.cancel();
            else {
                subscription = value;
                subscribed.complete(value);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> value) {
            queue.add(value);
        }

        @Override
        public void onError(Throwable error) {
            queue.add(error);
        }

        @Override
        public void onComplete() {
            queue.add(END);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            while (!buffer.hasRemaining()) {
                if (batch.hasNext()) {
                    buffer = batch.next();
                    continue;
                }
                Object item;
                try {
                    var active = subscribed.get();
                    synchronized (this) {
                        if (closed) throw new IOException("Body closed");
                        if (!requested) {
                            requested = true;
                            active.request(1);
                        }
                    }
                    item = queue.take();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Body read interrupted");
                } catch (ExecutionException error) {
                    throw new BodyReadException(error.getCause());
                }
                if (item == END) return -1;
                if (item instanceof Throwable error) throw new BodyReadException(error);
                @SuppressWarnings("unchecked")
                var buffers = (List<ByteBuffer>) item;
                batch = buffers.iterator();
                requested = false;
            }
            int count = Math.min(length, buffer.remaining());
            buffer.get(bytes, offset, count);
            return count;
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (subscription != null) subscription.cancel();
        }
    }
}
