package io.durablestreams.ktor

import io.durablestreams.server.core.DurableStreamsHandler
import io.durablestreams.server.core.HttpMethod
import io.durablestreams.server.core.ResponseBody
import io.durablestreams.server.core.ServerRequest
import io.durablestreams.server.core.ServerResponse
import io.durablestreams.server.core.SseFrame
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.Writer
import java.net.URI
import java.nio.file.StandardOpenOption
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow

object DurableStreamsKtorAdapter {
    suspend fun handle(call: ApplicationCall, handler: DurableStreamsHandler) {
        val request = toEngineRequest(call)
        val response = withContext(Dispatchers.IO) { handler.handle(request) }
        sendResponse(call, response)
    }

    private suspend fun toEngineRequest(call: ApplicationCall): ServerRequest {
        val request = call.request
        val method = HttpMethod.valueOf(request.httpMethod.value)
        val uri = toAbsoluteUri(request)
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (name in request.headers.names()) {
            headers[name] = request.headers.getAll(name)?.toMutableList() ?: mutableListOf()
        }

        val bytes = runCatching { call.receive<ByteArray>() }.getOrDefault(ByteArray(0))
        val body = if (bytes.isEmpty()) null else ByteArrayInputStream(bytes)
        return ServerRequest(method, uri, headers, body)
    }

    private fun toAbsoluteUri(request: ApplicationRequest): URI {
        val scheme = request.headers[HttpHeaders.XForwardedProto] ?: "http"
        val host = request.headers[HttpHeaders.Host] ?: "localhost"
        return URI.create("$scheme://$host${request.uri}")
    }

    private suspend fun sendResponse(call: ApplicationCall, response: ServerResponse) {
        val body = response.body()
        val isSse = body is ResponseBody.Sse

        response.headers().forEach { (name, values) ->
            if (isSse && name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                return@forEach
            }
            values.forEach { value ->
                call.response.header(name, value)
            }
        }

        val status = HttpStatusCode.fromValue(response.status())
        when (body) {
            is ResponseBody.Empty -> call.respond(status)
            is ResponseBody.Bytes -> call.respondBytes(body.bytes(), status = status)
            is ResponseBody.FileRegion -> call.respondOutputStream(status = status) {
                openRegionStream(body.region()).copyTo(this)
            }
            is ResponseBody.Sse -> {
                call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
                try {
                    call.respondTextWriter(status = status) {
                        streamSse(this, body.publisher())
                    }
                } catch (ex: Throwable) {
                    if (!isClientDisconnect(ex)) {
                        throw ex
                    }
                }
            }
        }
    }

    private fun isClientDisconnect(throwable: Throwable): Boolean {
        return when (throwable) {
            is ClosedByteChannelException,
            is ChannelWriteException,
            is java.io.IOException -> true
            else -> throwable.cause?.let { isClientDisconnect(it) } ?: false
        }
    }

    private suspend fun streamSse(writer: Writer, publisher: Flow.Publisher<SseFrame>) {
        withContext(Dispatchers.IO) {
            val done = CountDownLatch(1)
            publisher.subscribe(object : Flow.Subscriber<SseFrame> {
                private var subscription: Flow.Subscription? = null

                override fun onSubscribe(subscription: Flow.Subscription) {
                    this.subscription = subscription
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: SseFrame) {
                    try {
                        writer.write(item.render())
                        writer.flush()
                    } catch (ex: Exception) {
                        subscription?.cancel()
                        done.countDown()
                    }
                }

                override fun onError(throwable: Throwable) {
                    done.countDown()
                }

                override fun onComplete() {
                    done.countDown()
                }
            })
            done.await()
        }
    }

    private fun openRegionStream(region: io.durablestreams.server.spi.ReadOutcome.FileRegion): java.io.InputStream {
        return try {
            val channel = java.nio.channels.FileChannel.open(region.path(), StandardOpenOption.READ)
            channel.position(region.position())
            val inStream = java.nio.channels.Channels.newInputStream(channel)
            object : FilterInputStream(inStream) {
                private var remaining: Long = region.length().toLong()

                override fun read(): Int {
                    if (remaining <= 0) return -1
                    val value = super.read()
                    if (value >= 0) remaining--
                    return value
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val toRead = minOf(len.toLong(), remaining).toInt()
                    val read = super.read(b, off, toRead)
                    if (read > 0) remaining -= read.toLong()
                    return read
                }

                override fun close() {
                    super.close()
                    channel.close()
                }
            }
        } catch (ex: java.io.IOException) {
            java.io.InputStream.nullInputStream()
        }
    }
}
