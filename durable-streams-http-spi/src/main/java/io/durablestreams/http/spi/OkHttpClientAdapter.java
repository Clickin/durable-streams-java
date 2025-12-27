package io.durablestreams.http.spi;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link HttpClientAdapter} implementation using OkHttp.
 *
 * <p>Requires {@code com.squareup.okhttp3:okhttp} on the classpath.
 */
public final class OkHttpClientAdapter implements HttpClientAdapter {

    private final OkHttpClient httpClient;

    public OkHttpClientAdapter(OkHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public static OkHttpClientAdapter create() {
        return new OkHttpClientAdapter(new OkHttpClient());
    }

    public static OkHttpClientAdapter create(OkHttpClient httpClient) {
        return new OkHttpClientAdapter(httpClient);
    }

    @Override
    public HttpClientResponse send(HttpClientRequest request) throws HttpClientException {
        try {
            OkHttpClient client = clientWithTimeout(request);
            Request okRequest = toOkHttpRequest(request);
            Response response = client.newCall(okRequest).execute();
            return new ByteArrayResponse(response);
        } catch (SocketTimeoutException e) {
            throw new HttpTimeoutException(e);
        } catch (IOException e) {
            throw new HttpClientException(e);
        }
    }

    @Override
    public HttpClientResponse sendStreaming(HttpClientRequest request) throws HttpClientException {
        try {
            OkHttpClient client = clientWithTimeout(request);
            Request okRequest = toOkHttpRequest(request);
            Response response = client.newCall(okRequest).execute();
            return new StreamingResponse(response);
        } catch (SocketTimeoutException e) {
            throw new HttpTimeoutException(e);
        } catch (IOException e) {
            throw new HttpClientException(e);
        }
    }

    private OkHttpClient clientWithTimeout(HttpClientRequest request) {
        if (request.timeout() == null) {
            return httpClient;
        }
        long millis = request.timeout().toMillis();
        return httpClient.newBuilder()
                .readTimeout(millis, TimeUnit.MILLISECONDS)
                .writeTimeout(millis, TimeUnit.MILLISECONDS)
                .callTimeout(millis, TimeUnit.MILLISECONDS)
                .build();
    }

    private static Request toOkHttpRequest(HttpClientRequest request) {
        Request.Builder builder = new Request.Builder()
                .url(request.uri().toString());

        request.headers().forEach(builder::header);

        RequestBody body = null;
        if (request.body() != null) {
            String contentType = request.headers().get("Content-Type");
            MediaType mediaType = contentType != null ? MediaType.parse(contentType) : null;
            body = RequestBody.create(request.body(), mediaType);
        }

        String method = request.method();
        switch (method) {
            case "GET" -> builder.get();
            case "HEAD" -> builder.head();
            case "DELETE" -> { if (body != null) builder.delete(body); else builder.delete(); }
            case "POST" -> builder.post(body != null ? body : RequestBody.create(new byte[0], null));
            case "PUT" -> builder.put(body != null ? body : RequestBody.create(new byte[0], null));
            case "PATCH" -> builder.patch(body != null ? body : RequestBody.create(new byte[0], null));
            default -> builder.method(method, body);
        }

        return builder.build();
    }

    private static final class ByteArrayResponse implements HttpClientResponse {
        private final Response response;
        private final byte[] body;

        ByteArrayResponse(Response response) throws IOException {
            this.response = response;
            ResponseBody responseBody = response.body();
            this.body = responseBody != null ? responseBody.bytes() : null;
        }

        @Override public int statusCode() { return response.code(); }
        @Override public Optional<String> header(String name) { return Optional.ofNullable(response.header(name)); }
        @Override public byte[] body() { return body; }
        @Override public InputStream bodyAsStream() { return null; }
    }

    private static final class StreamingResponse implements HttpClientResponse {
        private final Response response;

        StreamingResponse(Response response) { this.response = response; }

        @Override public int statusCode() { return response.code(); }
        @Override public Optional<String> header(String name) { return Optional.ofNullable(response.header(name)); }
        @Override public byte[] body() { return null; }
        @Override public InputStream bodyAsStream() {
            ResponseBody body = response.body();
            return body != null ? body.byteStream() : null;
        }
    }
}
