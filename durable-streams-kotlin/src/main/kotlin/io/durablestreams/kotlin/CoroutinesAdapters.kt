package io.durablestreams.kotlin

import io.durablestreams.client.jdk.DurableStreamsClient
import io.durablestreams.client.jdk.LiveLongPollRequest
import io.durablestreams.client.jdk.LiveSseRequest
import io.durablestreams.core.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.FlowAdapters

class CoroutinesDurableStreamsClient(private val delegate: DurableStreamsClient) {

    fun subscribeLongPoll(request: LiveLongPollRequest): Flow<StreamEvent> {
        val pub = delegate.subscribeLongPoll(request)
        val rs = FlowAdapters.toPublisher(pub)
        return rs.asFlow()
    }

    fun subscribeSse(request: LiveSseRequest): Flow<StreamEvent> {
        val pub = delegate.subscribeSse(request)
        val rs = FlowAdapters.toPublisher(pub)
        return rs.asFlow()
    }
}