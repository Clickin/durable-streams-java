package io.github.clickin.core;

/**
 * Read mode for catch-up/live behavior.
 */
public enum ReadMode {
    /** Default catch-up read without live streaming. */
    CATCH_UP,
    
    /** Long-poll live mode with periodic reconnects. */
    LIVE_LONG_POLL,
    
    /** Server-Sent Events (SSE) live streaming. */
    LIVE_SSE
}