package io.durablestreams.kotlin

import io.durablestreams.client.jdk.DurableStreamsClient
import io.durablestreams.core.Offset
import io.durablestreams.core.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.FlowAdapters
import java.net.URI
import java.time.Duration

/**
 * Coroutine adapters for Durable Streams.
 *
 * Exposes Kotlin Flow over the Java Flow-based client.
 */
class CoroutinesDurableStreamsClient(private val delegate: DurableStreamsClient) {

    fun liveLongPoll(streamUrl: URI, offset: Offset, cursor: String? = null, timeout: Duration? = null): Flow<StreamEvent> {
        val pub = delegate.liveLongPoll(streamUrl, offset, cursor, timeout)
        val rs = FlowAdapters.toPublisher(pub)
        return rs.asFlow()
    }

    fun liveSse(streamUrl: URI, offset: Offset): Flow<StreamEvent> {
        val pub = delegate.liveSse(streamUrl, offset)
        val rs = FlowAdapters.toPublisher(pub)
        return rs.asFlow()
    }
}
