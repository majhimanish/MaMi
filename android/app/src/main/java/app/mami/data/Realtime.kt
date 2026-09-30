package app.mami.data

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Frames the server pushes over the live connection. */
@Serializable
sealed interface ServerFrame {
    @Serializable
    @SerialName("ready")
    data class Ready(val partner: PresenceDto? = null) : ServerFrame

    @Serializable
    @SerialName("accepted")
    data class Accepted(val id: String, @SerialName("at_ms") val atMs: Long) : ServerFrame

    @Serializable
    @SerialName("rejected")
    data class Rejected(val id: String, val reason: String) : ServerFrame

    @Serializable
    @SerialName("envelope")
    data class Envelope(
        val seq: Long? = null,
        val id: String,
        val kind: String,
        @SerialName("message_type") val messageType: Int? = null,
        val body: String? = null,
        @SerialName("at_ms") val atMs: Long,
    ) : ServerFrame {
        fun toDto() = EnvelopeDto(seq, id, kind, messageType, body, atMs)
    }

    @Serializable
    @SerialName("presence")
    data class Presence(val online: Boolean, @SerialName("last_seen_ms") val lastSeenMs: Long? = null) : ServerFrame

    @Serializable
    @SerialName("pairing")
    data class Pairing(val event: String) : ServerFrame
}

enum class ConnectionState { Offline, Connecting, Connected }

/**
 * The WebSocket that is open while the app is on screen. Reconnects with
 * back-off until [stop] is called.
 */
class Realtime(
    private val settings: Settings,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val client = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()
    private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 512)
    val frames: SharedFlow<ServerFrame> = _frames

    private val _state = MutableStateFlow(ConnectionState.Offline)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var wanted = false
    private var attempts = 0
    private var retry: Job? = null

    @Synchronized
    fun start() {
        wanted = true
        if (socket == null) connect()
    }

    @Synchronized
    fun stop() {
        wanted = false
        retry?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.value = ConnectionState.Offline
    }

    /** Reconnects now, for example after the network came back. */
    @Synchronized
    fun kick() {
        if (wanted && socket == null) {
            retry?.cancel()
            connect()
        }
    }

    fun sendEphemeral(request: SendRequestDto): Boolean = sendText(
        buildJsonObject {
            put("type", "send")
            put("id", request.id)
            put("kind", request.kind)
            put("message_type", request.messageType)
            put("body", request.body)
            put("push", request.push)
        }.toString(),
    )

    fun sendAck(seqs: List<Long>): Boolean = sendText(
        buildJsonObject {
            put("type", "ack")
            putJsonArray("seqs") { seqs.forEach { add(it) } }
        }.toString(),
    )

    @Synchronized
    private fun sendText(text: String): Boolean =
        _state.value == ConnectionState.Connected && socket?.send(text) == true

    private fun connect() {
        val token = settings.token ?: return
        val url = settings.serverUrl.replaceFirst("http", "ws") + "/v1/ws"
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad server address $url")
            return
        }
        _state.value = ConnectionState.Connecting
        socket = client.newWebSocket(request, Listener())
    }

    @Synchronized
    private fun onGone(ws: WebSocket) {
        if (ws !== socket) return
        socket = null
        _state.value = ConnectionState.Offline
        if (!wanted) return
        val backoff = (1000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L)
        attempts++
        retry = scope.launch {
            delay(backoff)
            synchronized(this@Realtime) { if (wanted && socket == null) connect() }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@Realtime) {
                if (webSocket !== socket) return
                attempts = 0
                _state.value = ConnectionState.Connected
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                MamiJson.decodeFromString(ServerFrame.serializer(), text)
            } catch (e: SerializationException) {
                Log.d(TAG, "ignoring unknown frame")
                return
            } catch (e: IllegalArgumentException) {
                return
            }
            _frames.tryEmit(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onGone(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "connection failed: ${t.message}")
            onGone(webSocket)
        }
    }

    private companion object {
        const val TAG = "MaMi.Realtime"
    }
}
