/**
 * Framework-neutral server core for Durable Streams.
 *
 * <p>Contains:
 * <ul>
 *   <li>{@link io.durablestreams.server.core.DurableStreamsHandler} (protocol handler)</li>
 *   <li>{@link io.durablestreams.server.core.InMemoryStreamStore} (reference store)</li>
 *   <li>Minimal SSE publisher and JSON validator for reference behavior</li>
 * </ul>
 *
 * <p>Framework integrations adapt {@link io.durablestreams.server.core.ServerRequest} and
 * {@link io.durablestreams.server.core.ServerResponse} to their HTTP runtimes.
 */
package io.durablestreams.server.core;
