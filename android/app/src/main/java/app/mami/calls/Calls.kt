package app.mami.calls

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

/** Where a call is. */
enum class CallPhase {
    /** Mine, the offer is on its way. */
    Calling,

    /** Mine, and their phone is ringing right now. */
    Ringing,

    /** Theirs, ringing on this phone. */
    Incoming,
    Connecting,
    Connected,

    /** The connection dropped; trying to get it back. */
    Reconnecting,
    Ended,
}

enum class AudioRoute(val label: String) {
    Earpiece("Phone"),
    Speaker("Speaker"),
    Bluetooth("Bluetooth"),
    Headset("Headphones"),
}

data class CallState(
    val id: String,
    val video: Boolean,
    val outgoing: Boolean,
    val phase: CallPhase,
    val startedAtMs: Long,
    val connectedAtMs: Long? = null,
    val muted: Boolean = false,
    val cameraOn: Boolean = video,
    val frontCamera: Boolean = true,
    val route: AudioRoute = if (video) AudioRoute.Speaker else AudioRoute.Earpiece,
    val routes: List<AudioRoute> = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
    val partnerMuted: Boolean = false,
    val partnerCameraOn: Boolean = video,
    /** Why it ended, in words: "Call ended", "Declined", "No answer"… */
    val endReason: String? = null,
)

/**
 * Voice and video calls with the partner. [CallManager] does the real thing
 * over WebRTC; the demo pretends.
 */
interface Calls {
    /** The current call, or null. Stays briefly in [CallPhase.Ended] so the screen can say why. */
    val call: StateFlow<CallState?>

    /** Real camera video can be shown (not in the demo or in tests). */
    val hasVideo: Boolean

    fun start(video: Boolean)
    fun accept()
    fun decline()
    fun hangUp()
    fun setMuted(muted: Boolean)
    fun setCameraOn(on: Boolean)
    fun switchCamera()
    fun setRoute(route: AudioRoute)

    /** A view showing my camera ([remote] false) or theirs; null without real video. */
    fun createVideoView(context: Context, remote: Boolean): View?
    fun releaseVideoView(view: View)
}

/** What the call engine needs from the messenger. */
interface CallSignals {
    val partnerName: String

    /** Paired, and the encrypted session is ready. */
    val canCall: Boolean

    /** Sends call signalling, end-to-end encrypted. [push] rings a closed app. */
    suspend fun sendCall(payload: app.mami.core.Payload, push: Boolean)

    /** Keep the live connection open while a call is going on, even in the background. */
    fun stayConnected(active: Boolean)

    /** Adds the call to the chat when it's over. */
    suspend fun logCall(id: String, outgoing: Boolean, video: Boolean, outcome: String, startedAtMs: Long, durationMs: Long?)
}

/** How a call ended, as stored on the call message in the chat. */
object CallOutcome {
    const val ANSWERED = "ANSWERED"
    const val MISSED = "MISSED"
    const val DECLINED = "DECLINED"
    const val BUSY = "BUSY"
    const val FAILED = "FAILED"
}
