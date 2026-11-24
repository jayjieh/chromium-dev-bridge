package dev.cdb.intellij

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.diagnostic.logger
import dev.cdb.protocol.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One event as received from the driver (data kept as JSON so the UI can render any event type). */
data class BridgeEvent(val type: String, val sessionId: String?, val tabId: String?, val timestamp: String?, val data: JsonNode)

/**
 * WebSocket JSON-RPC client for /api/v1/events. Subscribes to all browser events, reconnects with backoff, and
 * fans events out to listeners (called on a background thread; UI code must hop to the EDT).
 */
class EventStream(baseUrl: String, private val token: String) : AutoCloseable {
    private val log = logger<EventStream>()
    private val uri = URI.create(baseUrl.replaceFirst("http", "ws") + "/api/v1/events")
    private val http = HttpClient.newHttpClient()
    private val listeners = CopyOnWriteArrayList<(List<BridgeEvent>) -> Unit>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cdb-events").apply { isDaemon = true } }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var backoffMs = 500L

    fun addListener(l: (List<BridgeEvent>) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    fun start() {
        connect()
    }

    private fun connect() {
        if (closed) return
        http.newWebSocketBuilder().header("Authorization", "Bearer $token").connectTimeout(Duration.ofSeconds(5))
            .buildAsync(uri, Listener())
            .whenComplete { socket, error ->
                if (error != null) {
                    scheduleReconnect()
                } else {
                    ws = socket
                    backoffMs = 500
                    socket.sendText("""{"jsonrpc":"2.0","id":1,"method":"subscribe","params":{"events":["browser.*"]}}""", true)
                }
            }
    }

    private fun scheduleReconnect() {
        if (closed) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(15_000)
        scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                handle(text)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            ws = null
            scheduleReconnect()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            ws = null
            scheduleReconnect()
        }
    }

    internal fun handle(text: String) {
        val events = parse(text)
        if (events.isEmpty()) return
        listeners.forEach { l ->
            try {
                l(events)
            } catch (e: Exception) {
                log.warn("Event listener failed", e)
            }
        }
    }

    override fun close() {
        closed = true
        ws?.abort()
        scheduler.shutdownNow()
    }

    companion object {
        /** Parses an "events" notification; ignores responses and other messages. */
        fun parse(text: String): List<BridgeEvent> {
            val node = try { Json.mapper().readTree(text) } catch (e: Exception) { return emptyList() }
            if (node.path("method").asText() != "events") return emptyList()
            return node.path("params").path("events").map {
                BridgeEvent(
                    it.path("type").asText(), it.path("sessionId").textOrNull(), it.path("tabId").textOrNull(),
                    it.path("timestamp").textOrNull(), it.path("data")
                )
            }
        }

        private fun JsonNode.textOrNull(): String? = if (isMissingNode || isNull) null else asText()
    }
}
