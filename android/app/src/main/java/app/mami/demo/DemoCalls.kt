package app.mami.demo

import android.content.Context
import android.view.View
import app.mami.calls.AudioRoute
import app.mami.calls.CallOutcome
import app.mami.calls.CallPhase
import app.mami.calls.CallState
import app.mami.calls.Calls
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pretend calls with Maya: she picks up after a few rings (if auto replies are
 * on), can call you, and hangs up when asked. No microphone or camera is used;
 * the call screen shows her picture instead of video.
 */
class DemoCalls(
    private val scope: CoroutineScope,
    private val autoAnswer: () -> Boolean,
    private val log: (id: String, outgoing: Boolean, video: Boolean, outcome: String, startedAtMs: Long, durationMs: Long?) -> Unit,
) : Calls {
    private val _call = MutableStateFlow<CallState?>(null)
    override val call: StateFlow<CallState?> = _call.asStateFlow()
    override val hasVideo: Boolean = false
    private var job: Job? = null

    override fun start(video: Boolean) {
        if (_call.value?.phase.let { it != null && it != CallPhase.Ended }) return
        val state = CallState(UUID.randomUUID().toString(), video, outgoing = true, phase = CallPhase.Calling, startedAtMs = System.currentTimeMillis(), routes = routes)
        _call.value = state
        job = scope.launch {
            delay(1200)
            _call.update { it?.copy(phase = CallPhase.Ringing) }
            if (!autoAnswer()) {
                delay(45_000)
                end("No answer", CallOutcome.MISSED)
                return@launch
            }
            delay(3500)
            connect()
        }
    }

    /** Maya calls this phone. */
    fun incoming(video: Boolean) {
        if (_call.value?.phase.let { it != null && it != CallPhase.Ended }) return
        _call.value = CallState(UUID.randomUUID().toString(), video, outgoing = false, phase = CallPhase.Incoming, startedAtMs = System.currentTimeMillis(), routes = routes)
        job = scope.launch {
            delay(45_000)
            end("Missed call", CallOutcome.MISSED)
        }
    }

    override fun accept() {
        if (_call.value?.phase != CallPhase.Incoming) return
        job?.cancel()
        _call.update { it?.copy(phase = CallPhase.Connecting) }
        job = scope.launch {
            delay(900)
            connect()
        }
    }

    override fun decline() {
        if (_call.value?.phase == CallPhase.Incoming) end("Declined", CallOutcome.DECLINED)
    }

    override fun hangUp() {
        val state = _call.value ?: return
        when {
            state.phase == CallPhase.Incoming -> end("Declined", CallOutcome.DECLINED)
            state.connectedAtMs != null -> end("Call ended", CallOutcome.ANSWERED)
            else -> end("Call ended", CallOutcome.MISSED)
        }
    }

    /** Maya hangs up (or stops ringing). */
    fun partnerHangsUp() {
        val state = _call.value ?: return
        end(if (state.connectedAtMs != null) "Call ended" else if (state.outgoing) "Maya can't talk right now" else "Missed call", if (state.connectedAtMs != null) CallOutcome.ANSWERED else if (state.outgoing) CallOutcome.DECLINED else CallOutcome.MISSED)
    }

    /** Her connection drops for a few seconds. */
    fun partnerReconnects() {
        if (_call.value?.phase != CallPhase.Connected) return
        _call.update { it?.copy(phase = CallPhase.Reconnecting) }
        scope.launch {
            delay(3000)
            _call.update { if (it?.phase == CallPhase.Reconnecting) it.copy(phase = CallPhase.Connected) else it }
        }
    }

    fun partnerTogglesCamera() = _call.update { it?.copy(partnerCameraOn = !it.partnerCameraOn) }

    fun partnerTogglesMute() = _call.update { it?.copy(partnerMuted = !it.partnerMuted) }

    override fun setMuted(muted: Boolean) = _call.update { it?.copy(muted = muted) }

    override fun setCameraOn(on: Boolean) = _call.update { it?.copy(cameraOn = on) }

    override fun switchCamera() = _call.update { it?.copy(frontCamera = !it.frontCamera) }

    override fun setRoute(route: AudioRoute) = _call.update { it?.copy(route = route) }

    override fun createVideoView(context: Context, remote: Boolean): View? = null

    override fun releaseVideoView(view: View) = Unit

    /** A call frozen in one moment, for screenshots. */
    fun preview(phase: CallPhase, video: Boolean, outgoing: Boolean = true, minutes: Int = 12) {
        job?.cancel()
        val now = System.currentTimeMillis()
        val connected = phase == CallPhase.Connected || phase == CallPhase.Reconnecting
        _call.value = CallState(
            UUID.randomUUID().toString(), video, outgoing = outgoing, phase = phase,
            startedAtMs = now - minutes * 60_000L - 8_000,
            connectedAtMs = if (connected) now - minutes * 60_000L else null,
            route = if (video) AudioRoute.Speaker else AudioRoute.Earpiece,
            routes = routes,
        )
    }

    private fun connect() {
        _call.update { it?.copy(phase = CallPhase.Connected, connectedAtMs = System.currentTimeMillis()) }
    }

    private fun end(reason: String, outcome: String) {
        job?.cancel()
        val state = _call.value ?: return
        val now = System.currentTimeMillis()
        log(state.id, state.outgoing, state.video, outcome, state.startedAtMs, state.connectedAtMs?.let { now - it })
        _call.value = state.copy(phase = CallPhase.Ended, endReason = reason)
        job = scope.launch {
            delay(2200)
            if (_call.value?.id == state.id) _call.value = null
        }
    }

    private companion object {
        val routes = listOf(AudioRoute.Earpiece, AudioRoute.Speaker, AudioRoute.Bluetooth)
    }
}
