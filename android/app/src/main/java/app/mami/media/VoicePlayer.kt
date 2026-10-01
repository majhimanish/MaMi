package app.mami.media

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the voice-note player is doing. */
data class Playback(
    val messageId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

/** Plays one voice note at a time; starting another stops the first. */
object VoicePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(Playback())
    val state: StateFlow<Playback> = _state.asStateFlow()

    /** Plays or pauses the voice note of message [id]. */
    fun toggle(id: String, file: File) {
        val current = _state.value
        val player = player
        if (current.messageId == id && player != null) {
            if (player.isPlaying) {
                player.pause()
                _state.value = current.copy(playing = false)
            } else {
                player.start()
                _state.value = current.copy(playing = true)
                tick()
            }
            return
        }
        stop()
        try {
            val next = MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                setOnCompletionListener { stop() }
            }
            applySpeed(next, current.speed)
            next.start()
            this.player = next
            _state.value = Playback(id, true, 0, next.duration.toLong(), current.speed)
            tick()
        } catch (e: Exception) {
            Log.w("MaMi.Voice", "could not play voice note", e)
            stop()
        }
    }

    /** 1× → 1.5× → 2× → 1×. */
    fun cycleSpeed() {
        val next = when (_state.value.speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player?.let { applySpeed(it, next) }
        _state.value = _state.value.copy(speed = next)
    }

    fun seek(id: String, fraction: Float) {
        val player = player ?: return
        if (_state.value.messageId != id) return
        val to = (player.duration * fraction.coerceIn(0f, 1f)).toInt()
        player.seekTo(to)
        _state.value = _state.value.copy(positionMs = to.toLong())
    }

    fun stop() {
        ticker?.cancel()
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        _state.value = Playback(speed = _state.value.speed)
    }

    private fun applySpeed(player: MediaPlayer, speed: Float) {
        runCatching {
            val wasPlaying = player.isPlaying
            player.playbackParams = PlaybackParams().setSpeed(speed)
            // Setting params starts a paused player; keep it paused.
            if (!wasPlaying) player.pause()
        }
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val player = player ?: break
                _state.value = _state.value.copy(positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0))
                delay(60)
            }
        }
    }
}
