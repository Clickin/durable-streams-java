package io.github.clickin.core;

/**
 * Durable Streams protocol constants (query keys, header names, and well-known values).
 *
 * <p>This module intentionally contains no HTTP client/server bindings and no reactive framework dependencies.
 * It only models protocol-level concerns that are shared across clients and servers.
 */
public final class Protocol {
    private Protocol() {}

    // Query parameter keys
    public static final String Q_OFFSET = "offset";
    public static final String Q_LIVE = "live";
    public static final String Q_CURSOR = "cursor";

    // live modes
    public static final String LIVE_LONG_POLL = "long-poll";
    public static final String LIVE_SSE = "sse";

    // Response/request headers
    public static final String H_STREAM_NEXT_OFFSET = "Stream-Next-Offset";
    public static final String H_STREAM_UP_TO_DATE = "Stream-Up-To-Date";
    public static final String H_STREAM_CURSOR = "Stream-Cursor";
    public static final String H_STREAM_TTL = "Stream-TTL";
    public static final String H_STREAM_EXPIRES_AT = "Stream-Expires-At";
    public static final String H_STREAM_SEQ = "Stream-Seq";

    // HTTP headers
    public static final String H_ETAG = "ETag";
    public static final String H_IF_NONE_MATCH = "If-None-Match";
    public static final String H_CONTENT_TYPE = "Content-Type";
    public static final String H_ACCEPT = "Accept";

    // Content types
    public static final String CT_EVENT_STREAM = "text/event-stream";

    /** Sentinel offset that represents "from the start of the stream". */
    public static final String OFFSET_BEGINNING = "-1";

    /** Canonical boolean textual value used by the protocol for true. */
    public static final String BOOL_TRUE = "true";
}
