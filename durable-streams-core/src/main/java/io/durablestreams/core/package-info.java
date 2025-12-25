/**
 * Protocol-centric core for Durable Streams.
 *
 * <p>This module is deliberately framework-neutral. It contains only:
 * <ul>
 *   <li>Protocol constants and small models</li>
 *   <li>Lightweight utilities (header lookup, lexicographic query ordering)</li>
 *   <li>A minimal SSE parser and control-event JSON parser</li>
 * </ul>
 *
 * <p>HTTP client/server bindings live in other modules.
 */
package io.durablestreams.core;
