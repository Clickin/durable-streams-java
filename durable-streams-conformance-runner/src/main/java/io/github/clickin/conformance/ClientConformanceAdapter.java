package io.github.clickin.conformance;

import io.github.clickin.client.AppendRequest;
import io.github.clickin.client.AppendResult;
import io.github.clickin.client.CreateRequest;
import io.github.clickin.client.CreateResult;
import io.github.clickin.client.DurableStreamsClient;
import io.github.clickin.client.HeadResult;
import io.github.clickin.client.LiveLongPollRequest;
import io.github.clickin.client.LiveSseRequest;
import io.github.clickin.client.ReadRequest;
import io.github.clickin.client.ReadResult;
import io.github.clickin.core.Offset;
import io.github.clickin.core.Protocol;
import io.github.clickin.core.StreamEvent;
import io.github.clickin.json.jackson.JacksonJsonCodec;
import io.github.clickin.json.spi.JsonCodec;
import io.github.clickin.json.spi.JsonException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientConformanceAdapter {
    private static final JsonCodec JSON = new JacksonJsonCodec();
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_MAX_CHUNKS = 100;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100L;

    private final DurableStreamsClient client;
    private final Map<String, String> contentTypes = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String serverUrl;

    public ClientConformanceAdapter(DurableStreamsClient client) {
        this.client = client;
    }

    public static void main(String[] args) throws Exception {
        new ClientConformanceAdapter(DurableStreamsClient.create()).run();
    }

    private void run() throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        java.io.PrintStream utf8Out = new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }

            String commandType = null;
            Map<String, Object> result;
            try {
                Map<String, Object> command = readCommand(line);
                commandType = text(command, "type");
                result = handleCommand(command);
            } catch (Exception e) {
                result = errorNode(commandType == null ? "init" : commandType, null, "PARSE_ERROR",
                        "Failed to parse command: " + e.getMessage());
            }

            utf8Out.println(JSON.writeString(result));

            if ("shutdown".equals(commandType)) {
                break;
            }
        }
    }

    private Map<String, Object> readCommand(String json) throws JsonException {
        @SuppressWarnings("unchecked")
        Map<String, Object> command = JSON.readValue(json, Map.class);
        if (command == null) {
            return Map.of();
        }
        return command;
    }

    private Map<String, Object> handleCommand(Map<String, Object> command) {
        String type = text(command, "type");
        if (type == null) {
            return errorNode("init", null, "PARSE_ERROR", "Missing command type");
        }

        try {
            return switch (type) {
                case "init" -> handleInit(command);
                case "create" -> handleCreate(command);
                case "connect" -> handleConnect(command);
                case "append" -> handleAppend(command);
                case "read" -> handleRead(command);
                case "head" -> handleHead(command);
                case "delete" -> handleDelete(command);
                case "shutdown" -> successNode("shutdown");
                case "set-dynamic-header", "set-dynamic-param", "clear-dynamic", "benchmark" ->
                        errorNode(type, null, "NOT_SUPPORTED", "Operation not supported");
                default -> errorNode(type, null, "NOT_SUPPORTED", "Unknown command type: " + type);
            };
        } catch (Exception e) {
            return errorFromException(type, e);
        }
    }

    private Map<String, Object> handleInit(Map<String, Object> command) {
        this.serverUrl = text(command, "serverUrl");
        contentTypes.clear();

        Map<String, Object> result = successNode("init");
        result.put("clientName", "durable-streams-java-jdk");
        result.put("clientVersion", "dev");

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("batching", false);
        features.put("sse", true);
        features.put("longPoll", true);
        features.put("streaming", true);
        features.put("dynamicHeaders", false);
        result.put("features", features);

        return result;
    }

    private Map<String, Object> handleCreate(Map<String, Object> command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);
        String contentType = text(command, "contentType");
        if (contentType == null || contentType.isBlank()) {
            contentType = DEFAULT_CONTENT_TYPE;
        }

        Map<String, String> headers = mergeHeaders(mapValue(command, "headers"));
        Long ttlSeconds = longValue(command, "ttlSeconds");
        if (ttlSeconds != null) {
            headers.put(Protocol.H_STREAM_TTL, ttlSeconds.toString());
        }
        String expiresAt = text(command, "expiresAt");
        if (expiresAt != null && !expiresAt.isBlank()) {
            headers.put(Protocol.H_STREAM_EXPIRES_AT, expiresAt);
        }

        CreateRequest request = new CreateRequest(uri, contentType, headers.isEmpty() ? null : headers, null);
        CreateResult result = client.create(request);

        if (result.status() >= 400) {
            return errorForStatus("create", result.status(), command);
        }

        contentTypes.put(path, contentType);

        Map<String, Object> out = successNode("create");
        out.put("status", result.status());
        if (result.nextOffset() != null) {
            out.put("offset", result.nextOffset().value());
        }
        return out;
    }

    private Map<String, Object> handleConnect(Map<String, Object> command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        HeadResult head = client.head(uri);
        if (head.status() >= 400) {
            return errorForStatus("connect", head.status(), command);
        }

        if (head.contentType() != null) {
            contentTypes.put(path, head.contentType());
        }

        Map<String, Object> out = successNode("connect");
        out.put("status", 200);
        if (head.nextOffset() != null) {
            out.put("offset", head.nextOffset().value());
        }
        return out;
    }

    private Map<String, Object> handleAppend(Map<String, Object> command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        String contentType = contentTypes.get(path);
        if (contentType == null) {
            contentType = resolveContentTypeFallback(uri, path);
        }

        Map<String, String> headers = mergeHeaders(mapValue(command, "headers"));
        Long seq = longValue(command, "seq");
        if (seq != null) {
            headers.put(Protocol.H_STREAM_SEQ, seq.toString());
        }

        byte[] body = decodeBody(command);
        Map<String, String> finalHeaders = headers.isEmpty() ? null : headers;
        String finalContentType = contentType;

        AppendResult result = executeWithRetries(
                () -> client.append(new AppendRequest(uri, finalContentType, finalHeaders, new ByteArrayInputStream(body))),
                AppendResult::status);

        if (result.status() >= 400) {
            return errorForStatus("append", result.status(), command);
        }

        Map<String, Object> out = successNode("append");
        out.put("status", normalizeSuccessStatus(result.status()));
        if (result.nextOffset() != null) {
            out.put("offset", result.nextOffset().value());
        }
        return out;
    }

    private Map<String, Object> handleRead(Map<String, Object> command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        Offset offset;
        try {
            offset = parseOffset(text(command, "offset"));
        } catch (IllegalArgumentException e) {
            return errorNode("read", 400, "INVALID_OFFSET", e.getMessage());
        }

        String live = text(command, "live");
        int timeoutMs = intValue(command, "timeoutMs", DEFAULT_TIMEOUT_MS);
        int maxChunks = intValue(command, "maxChunks", DEFAULT_MAX_CHUNKS);
        boolean waitForUpToDate = booleanValue(command, "waitForUpToDate");

        if ("long-poll".equals(live)) {
            return readLive(uri, offset, false, timeoutMs, maxChunks, waitForUpToDate);
        }
        if ("sse".equals(live)) {
            return readLive(uri, offset, true, timeoutMs, maxChunks, waitForUpToDate);
        }
        return readCatchUp(uri, offset);
    }

    private Map<String, Object> readCatchUp(URI uri, Offset offset) throws Exception {
        ReadRequest request = new ReadRequest(uri, offset, null);
        ReadResult result = executeWithRetries(() -> client.readCatchUp(request), ReadResult::status);

        if (result.status() >= 400) {
            return errorForStatus("read", result.status(), null);
        }

        Map<String, Object> out = successNode("read");
        out.put("status", 200);
        List<Map<String, Object>> chunks = new ArrayList<>();
        out.put("chunks", chunks);

        byte[] body = result.body();
        if (body != null && body.length > 0) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("data", new String(body, StandardCharsets.UTF_8));
            if (result.nextOffset() != null) {
                chunk.put("offset", result.nextOffset().value());
            }
            chunks.add(chunk);
        }

        if (result.nextOffset() != null) {
            out.put("offset", result.nextOffset().value());
        }
        out.put("upToDate", result.upToDate());
        return out;
    }

    private Map<String, Object> readLive(
            URI uri,
            Offset offset,
            boolean sse,
            int timeoutMs,
            int maxChunks,
            boolean waitForUpToDate
    ) throws Exception {
        LiveReadState state = new LiveReadState(maxChunks, waitForUpToDate);

        Flow.Publisher<StreamEvent> publisher = sse
                ? client.subscribeSse(new LiveSseRequest(uri, offset))
                : client.subscribeLongPoll(new LiveLongPollRequest(uri, offset, null, Duration.ofMillis(timeoutMs)));

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                state.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(StreamEvent item) {
                if (state.finished.get()) {
                    return;
                }

                if (item instanceof StreamEvent.Data data) {
                    String payload = decodeUtf8(data.bytes());
                    state.chunks.add(new Chunk(payload));
                    int count = state.chunkCount.incrementAndGet();
                    if (count >= state.maxChunks) {
                        state.finish();
                    }
                    return;
                }

                if (item instanceof StreamEvent.Control control) {
                    String nextOffset = control.streamNextOffset().value();
                    state.lastOffset.set(nextOffset);

                    Chunk last = state.lastChunk();
                    if (last != null && last.offset == null) {
                        last.offset = nextOffset;
                    }

                    if (sse && state.waitForUpToDate && isUpToDate(uri, nextOffset)) {
                        state.upToDate.set(true);
                        state.finish();
                    }
                    return;
                }

                if (item instanceof StreamEvent.UpToDate upToDate) {
                    state.upToDate.set(true);
                    state.lastOffset.set(upToDate.nextOffset().value());
                    if (state.waitForUpToDate) {
                        state.finish();
                    }
                }
            }

            @Override
            public void onError(Throwable throwable) {
                state.error.set(throwable);
                state.finish();
            }

            @Override
            public void onComplete() {
                state.finish();
            }
        });

        boolean completed = state.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {
            state.timedOut = true;
            state.cancel();
        }

        if (state.error.get() != null) {
            return errorFromException("read", state.error.get());
        }

        boolean upToDate = state.upToDate.get();
        if (!upToDate && state.timedOut && state.chunks.isEmpty()) {
            upToDate = true;
        }

        Map<String, Object> out = successNode("read");
        out.put("status", 200);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (Chunk chunk : state.chunks) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("data", chunk.data);
            if (chunk.offset != null) {
                c.put("offset", chunk.offset);
            }
            chunks.add(c);
        }
        out.put("chunks", chunks);

        String finalOffset = state.lastOffset.get();
        if (finalOffset == null && offset != null) {
            finalOffset = offset.value();
        }
        if (finalOffset != null) {
            out.put("offset", finalOffset);
        }
        out.put("upToDate", upToDate);
        return out;
    }

    private Map<String, Object> handleHead(Map<String, Object> command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        HeadResult result = client.head(uri);
        if (result.status() >= 400) {
            return errorForStatus("head", result.status(), command);
        }

        if (result.contentType() != null) {
            contentTypes.put(path, result.contentType());
        }

        Map<String, Object> out = successNode("head");
        out.put("status", 200);
        if (result.nextOffset() != null) {
            out.put("offset", result.nextOffset().value());
        }
        if (result.contentType() != null) {
            out.put("contentType", result.contentType());
        }
        if (result.ttlSeconds() != null) {
            out.put("ttlSeconds", result.ttlSeconds());
        }
        if (result.expiresAt() != null) {
            out.put("expiresAt", result.expiresAt().toString());
        }
        return out;
    }

    private Map<String, Object> handleDelete(Map<String, Object> command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        HeadResult head = client.head(uri);
        if (head.status() >= 400) {
            return errorForStatus("delete", head.status(), command);
        }

        client.delete(uri);
        contentTypes.remove(path);

        Map<String, Object> out = successNode("delete");
        out.put("status", 200);
        return out;
    }

    private static String text(Map<String, Object> node, String field) {
        Object value = node == null ? null : node.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return String.valueOf(value);
    }

    private static String require(Map<String, Object> node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private static Long longValue(Map<String, Object> node, String field) {
        Object value = node == null ? null : node.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intValue(Map<String, Object> node, String field, int defaultValue) {
        Object value = node == null ? null : node.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean booleanValue(Map<String, Object> node, String field) {
        Object value = node == null ? null : node.get(field);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> node, String field) {
        Object value = node == null ? null : node.get(field);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static Offset parseOffset(String offset) {
        if (offset == null || offset.isBlank()) {
            return Offset.beginning();
        }
        return new Offset(offset);
    }

    private Map<String, String> mergeHeaders(Map<String, Object> headersNode) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersNode != null) {
            for (Map.Entry<String, Object> entry : headersNode.entrySet()) {
                if (entry.getValue() != null) {
                    headers.put(entry.getKey(), entry.getValue().toString());
                }
            }
        }
        return headers;
    }

    private URI resolveStreamUri(String path) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalStateException("Adapter not initialized");
        }
        URI base = URI.create(serverUrl);
        return base.resolve(path);
    }

    private String resolveContentTypeFallback(URI uri, String path) {
        try {
            HeadResult head = client.head(uri);
            if (head.status() < 400 && head.contentType() != null) {
                contentTypes.put(path, head.contentType());
                return head.contentType();
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_CONTENT_TYPE;
    }

    private static byte[] decodeBody(Map<String, Object> command) {
        String data = text(command, "data");
        if (data == null) {
            data = "";
        }
        boolean binary = booleanValue(command, "binary");
        if (binary) {
            return Base64.getDecoder().decode(data);
        }
        return data.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8(ByteBuffer buffer) {
        ByteBuffer dup = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> successNode(String type) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", type);
        node.put("success", true);
        return node;
    }

    private static Map<String, Object> errorForStatus(String commandType, int status, Map<String, Object> command) {
        String code = errorCodeForStatus(commandType, status, command);
        return errorNode(commandType, status, code, "HTTP " + status);
    }

    private static Map<String, Object> errorNode(String commandType, Integer status, String errorCode, String message) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "error");
        node.put("success", false);
        node.put("commandType", commandType);
        if (status != null) {
            node.put("status", status);
        }
        node.put("errorCode", errorCode);
        node.put("message", message);
        return node;
    }

    private static Map<String, Object> errorFromException(String commandType, Throwable error) {
        String code = "INTERNAL_ERROR";
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        Integer status = null;

        if (isTimeout(error)) {
            code = "TIMEOUT";
        } else if (isNetworkError(error)) {
            code = "NETWORK_ERROR";
        } else {
            Integer extractedStatus = extractHttpStatus(error);
            if (extractedStatus != null) {
                status = extractedStatus;
                code = errorCodeForStatus(commandType, extractedStatus, null);
            }
        }

        return errorNode(commandType, status, code, message);
    }

    private static Integer extractHttpStatus(Throwable error) {
        String msg = error.getMessage();
        if (msg == null) return null;

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("status=(\\d+)").matcher(msg);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static boolean isTimeout(Throwable error) {
        return error instanceof HttpTimeoutException
                || error instanceof java.net.SocketTimeoutException;
    }

    private static boolean isNetworkError(Throwable error) {
        return error instanceof java.net.ConnectException
                || error instanceof java.net.UnknownHostException;
    }

    private static String errorCodeForStatus(String commandType, int status, Map<String, Object> command) {
        if (status == 404 || status == 410) {
            return "NOT_FOUND";
        }
        if (status == 409) {
            if ("append".equals(commandType) && command != null && command.containsKey("seq")) {
                return "SEQUENCE_CONFLICT";
            }
            return "CONFLICT";
        }
        if (status == 400) {
            if ("read".equals(commandType)) {
                return "INVALID_OFFSET";
            }
            return "UNEXPECTED_STATUS";
        }
        if (status == 501) {
            return "NOT_SUPPORTED";
        }
        if (status == 408 || status == 504) {
            return "TIMEOUT";
        }
        return "UNEXPECTED_STATUS";
    }

    private static int normalizeSuccessStatus(int status) {
        if (status == 204) {
            return 200;
        }
        return status;
    }

    private boolean isUpToDate(URI uri, String offset) {
        try {
            HeadResult head = client.head(uri);
            if (head.status() >= 400 || head.nextOffset() == null) {
                return false;
            }
            return head.nextOffset().value().equals(offset);
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T executeWithRetries(CallableWithStatus<T> callable, StatusExtractor<T> statusExtractor) throws Exception {
        int attempt = 0;
        T result = null;
        while (attempt < MAX_RETRIES) {
            attempt++;
            result = callable.call();
            int status = statusExtractor.status(result);
            if (!shouldRetry(status) || attempt >= MAX_RETRIES) {
                return result;
            }
            Thread.sleep(RETRY_DELAY_MS);
        }
        return result;
    }

    private static boolean shouldRetry(int status) {
        return status == 500 || status == 503 || status == 429;
    }

    @FunctionalInterface
    private interface CallableWithStatus<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface StatusExtractor<T> {
        int status(T result);
    }

    private static final class Chunk {
        private final String data;
        private String offset;

        private Chunk(String data) {
            this.data = data;
        }
    }

    private static final class LiveReadState {
        private final List<Chunk> chunks = new ArrayList<>();
        private final AtomicInteger chunkCount = new AtomicInteger();
        private final AtomicReference<String> lastOffset = new AtomicReference<>();
        private final AtomicBoolean upToDate = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Flow.Subscription subscription;
        private volatile boolean timedOut;

        private final int maxChunks;
        private final boolean waitForUpToDate;

        private LiveReadState(int maxChunks, boolean waitForUpToDate) {
            this.maxChunks = maxChunks;
            this.waitForUpToDate = waitForUpToDate;
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                done.countDown();
                cancel();
            }
        }

        private void cancel() {
            Flow.Subscription sub = subscription;
            if (sub != null) {
                sub.cancel();
            }
        }

        private Chunk lastChunk() {
            if (chunks.isEmpty()) {
                return null;
            }
            return chunks.get(chunks.size() - 1);
        }
    }
}
