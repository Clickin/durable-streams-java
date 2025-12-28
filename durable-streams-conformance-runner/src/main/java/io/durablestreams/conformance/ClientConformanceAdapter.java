package io.durablestreams.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.durablestreams.client.jdk.AppendRequest;
import io.durablestreams.client.jdk.AppendResult;
import io.durablestreams.client.jdk.CreateRequest;
import io.durablestreams.client.jdk.CreateResult;
import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.client.jdk.HeadResult;
import io.durablestreams.client.jdk.LiveLongPollRequest;
import io.durablestreams.client.jdk.LiveSseRequest;
import io.durablestreams.client.jdk.ReadRequest;
import io.durablestreams.client.jdk.ReadResult;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.StreamEvent;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientConformanceAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
            ObjectNode result;
            try {
                JsonNode command = MAPPER.readTree(line);
                commandType = text(command, "type");
                result = handleCommand(command);
            } catch (Exception e) {
                result = errorNode(commandType == null ? "init" : commandType, null, "PARSE_ERROR",
                        "Failed to parse command: " + e.getMessage());
            }

            utf8Out.println(MAPPER.writeValueAsString(result));

            if ("shutdown".equals(commandType)) {
                break;
            }
        }
    }

    private ObjectNode handleCommand(JsonNode command) {
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

    private ObjectNode handleInit(JsonNode command) {
        this.serverUrl = text(command, "serverUrl");
        contentTypes.clear();

        ObjectNode result = successNode("init");
        result.put("clientName", "durable-streams-java-jdk");
        result.put("clientVersion", "dev");

        ObjectNode features = result.putObject("features");
        features.put("batching", false);
        features.put("sse", true);
        features.put("longPoll", true);
        features.put("streaming", true);
        features.put("dynamicHeaders", false);

        return result;
    }

    private ObjectNode handleCreate(JsonNode command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);
        String contentType = text(command, "contentType");
        if (contentType == null || contentType.isBlank()) {
            contentType = DEFAULT_CONTENT_TYPE;
        }

        Map<String, String> headers = mergeHeaders(command.get("headers"));
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

        ObjectNode out = successNode("create");
        out.put("status", result.status());
        if (result.nextOffset() != null) {
            out.put("offset", result.nextOffset().value());
        }
        return out;
    }

    private ObjectNode handleConnect(JsonNode command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        HeadResult head = client.head(uri);
        if (head.status() >= 400) {
            return errorForStatus("connect", head.status(), command);
        }

        if (head.contentType() != null) {
            contentTypes.put(path, head.contentType());
        }

        ObjectNode out = successNode("connect");
        out.put("status", 200);
        if (head.nextOffset() != null) {
            out.put("offset", head.nextOffset().value());
        }
        return out;
    }

    private ObjectNode handleAppend(JsonNode command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        String contentType = contentTypes.get(path);
        if (contentType == null) {
            contentType = resolveContentTypeFallback(uri, path);
        }

        Map<String, String> headers = mergeHeaders(command.get("headers"));
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

        ObjectNode out = successNode("append");
        out.put("status", normalizeSuccessStatus(result.status()));
        if (result.nextOffset() != null) {
            out.put("offset", result.nextOffset().value());
        }
        return out;
    }

    private ObjectNode handleRead(JsonNode command) throws Exception {
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

    private ObjectNode readCatchUp(URI uri, Offset offset) throws Exception {
        ReadRequest request = new ReadRequest(uri, offset, null);
        ReadResult result = executeWithRetries(() -> client.readCatchUp(request), ReadResult::status);

        if (result.status() >= 400) {
            return errorForStatus("read", result.status(), null);
        }

        ObjectNode out = successNode("read");
        out.put("status", 200);
        ArrayNode chunks = out.putArray("chunks");

        byte[] body = result.body();
        if (body != null && body.length > 0) {
            ObjectNode chunk = chunks.addObject();
            chunk.put("data", new String(body, StandardCharsets.UTF_8));
            if (result.nextOffset() != null) {
                chunk.put("offset", result.nextOffset().value());
            }
        }

        if (result.nextOffset() != null) {
            out.put("offset", result.nextOffset().value());
        }
        out.put("upToDate", result.upToDate());
        return out;
    }

    private ObjectNode readLive(
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

        ObjectNode out = successNode("read");
        out.put("status", 200);
        ArrayNode chunks = out.putArray("chunks");
        for (Chunk chunk : state.chunks) {
            ObjectNode c = chunks.addObject();
            c.put("data", chunk.data);
            if (chunk.offset != null) {
                c.put("offset", chunk.offset);
            }
        }

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

    private ObjectNode handleHead(JsonNode command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        HeadResult result = client.head(uri);
        if (result.status() >= 400) {
            return errorForStatus("head", result.status(), command);
        }

        if (result.contentType() != null) {
            contentTypes.put(path, result.contentType());
        }

        ObjectNode out = successNode("head");
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

    private ObjectNode handleDelete(JsonNode command) throws Exception {
        String path = require(command, "path");
        URI uri = resolveStreamUri(path);

        HeadResult head = client.head(uri);
        if (head.status() >= 400) {
            return errorForStatus("delete", head.status(), command);
        }

        client.delete(uri);
        contentTypes.remove(path);

        ObjectNode out = successNode("delete");
        out.put("status", 200);
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static String require(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        return child.asLong();
    }

    private static int intValue(JsonNode node, String field, int defaultValue) {
        JsonNode child = node == null ? null : node.get(field);
        if (child == null || child.isNull()) {
            return defaultValue;
        }
        return child.asInt(defaultValue);
    }

    private static boolean booleanValue(JsonNode node, String field) {
        JsonNode child = node == null ? null : node.get(field);
        return child != null && !child.isNull() && child.asBoolean();
    }

    private static Offset parseOffset(String offset) {
        if (offset == null || offset.isBlank()) {
            return Offset.beginning();
        }
        return new Offset(offset);
    }

    private Map<String, String> mergeHeaders(JsonNode headersNode) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersNode != null && headersNode.isObject()) {
            headersNode.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    headers.put(entry.getKey(), entry.getValue().asText());
                }
            });
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

    private static byte[] decodeBody(JsonNode command) {
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

    private static ObjectNode successNode(String type) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("success", true);
        return node;
    }

    private static ObjectNode errorForStatus(String commandType, int status, JsonNode command) {
        String code = errorCodeForStatus(commandType, status, command);
        return errorNode(commandType, status, code, "HTTP " + status);
    }

    private static ObjectNode errorNode(String commandType, Integer status, String errorCode, String message) {
        ObjectNode node = MAPPER.createObjectNode();
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

    private static ObjectNode errorFromException(String commandType, Throwable error) {
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

    private static String errorCodeForStatus(String commandType, int status, JsonNode command) {
        if (status == 404 || status == 410) {
            return "NOT_FOUND";
        }
        if (status == 409) {
            if ("append".equals(commandType) && command != null && command.hasNonNull("seq")) {
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
        private final java.util.List<Chunk> chunks = new java.util.ArrayList<>();
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
