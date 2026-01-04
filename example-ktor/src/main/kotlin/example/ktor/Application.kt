package example.ktor

import io.github.clickin.ktor.DurableStreamsKtorAdapter
import io.github.clickin.server.core.CachePolicy
import io.github.clickin.server.core.DurableStreamsHandler
import io.github.clickin.server.core.InMemoryStreamStore
import io.github.clickin.server.spi.CursorPolicy
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import java.time.Clock
import java.time.Duration

fun main() {
    embeddedServer(Netty, port = 4435, module = Application::module).start(wait = true)
}

fun Application.module() {
    val handler = DurableStreamsHandler.builder(InMemoryStreamStore())
        .cursorPolicy(CursorPolicy(Clock.systemUTC()))
        .cachePolicy(CachePolicy.defaultPrivate())
        .longPollTimeout(Duration.ofSeconds(25))
        .sseMaxDuration(Duration.ofSeconds(60))
        .build()

    routing {
        route("{path...}") {
            handle {
                DurableStreamsKtorAdapter.handle(call, handler)
            }
        }
    }
}
