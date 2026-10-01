package app.mami.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A finished voice note. */
class VoiceRecording(val file: File, val durationMs: Long, val levels: ByteArray)

/**
 * Records voice notes as AAC in an MP4 container (what every phone plays),
 * sampling the loudness as it goes so the bubble can draw a waveform.
 */
class VoiceRecorder(private val context: Context, private val scope: CoroutineScope) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var sampler: Job? = null
    private val samples = ArrayList<Int>()

    private val _level = MutableStateFlow(0f)
    /** Current loudness, 0..1, for the live meter. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    val recording: Boolean get() = recorder != null

    /** Returns false if the microphone couldn't be started. */
    fun start(into: File): Boolean {
        cancel()
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setMaxDuration(MAX_DURATION_MS)
            recorder.setOutputFile(into.path)
            recorder.prepare()
            recorder.start()
            this.recorder = recorder
            file = into
            startedAt = System.currentTimeMillis()
            samples.clear()
            sampler = scope.launch {
                while (isActive) {
                    val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
                    // Square root makes quiet speech visible without clipping loud bits.
                    val level = sqrt(amplitude / 32767f).coerceIn(0f, 1f)
                    samples += (level * 255).toInt()
                    _level.value = level
                    _elapsedMs.value = System.currentTimeMillis() - startedAt
                    delay(SAMPLE_MS)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start recording", e)
            runCatching { recorder.release() }
            into.delete()
            false
        }
    }

    /** Stops and returns the recording, or null if it was too short to keep. */
    fun stop(): VoiceRecording? {
        val recorder = recorder ?: return null
        val file = file
        val duration = System.currentTimeMillis() - startedAt
        sampler?.cancel()
        val ok = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        this.recorder = null
        this.file = null
        _level.value = 0f
        _elapsedMs.value = 0
        if (!ok || file == null || duration < MIN_DURATION_MS) {
            file?.delete()
            return null
        }
        return VoiceRecording(file, duration, bars(samples, BARS))
    }

    fun cancel() {
        val recorder = recorder ?: return
        sampler?.cancel()
        runCatching { recorder.stop() }
        recorder.release()
        this.recorder = null
        file?.delete()
        file = null
        _level.value = 0f
        _elapsedMs.value = 0
    }

    companion object {
        private const val TAG = "MaMi.Voice"
        private const val SAMPLE_MS = 70L
        const val MIN_DURATION_MS = 700L
        const val MAX_DURATION_MS = 15 * 60_000
        const val BARS = 48

        /** Squeezes any number of samples into [count] bars, keeping the peaks. */
        fun bars(samples: List<Int>, count: Int): ByteArray {
            if (samples.isEmpty()) return ByteArray(count) { 8 }
            val peak = samples.max().coerceAtLeast(1)
            return ByteArray(count) { i ->
                val from = i * samples.size / count
                val to = ((i + 1) * samples.size / count).coerceAtLeast(from + 1).coerceAtMost(samples.size)
                val value = (samples.subList(from.coerceAtMost(samples.size - 1), to).max() * 255 / peak).coerceIn(8, 255)
                value.toByte()
            }
        }
    }
}
