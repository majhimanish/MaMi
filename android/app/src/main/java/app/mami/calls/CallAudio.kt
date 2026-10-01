package app.mami.calls

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Everything audio about a call: the ringtone (respecting silent and
 * vibrate), the call audio mode, earpiece / speaker / Bluetooth / headphones,
 * and the proximity sensor that turns the screen off at the ear.
 */
class CallAudio(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var focus: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null
    private var active = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /** Rings like a phone call: ringtone unless silenced, vibration unless fully silent. */
    fun ring() {
        stopRinging()
        val mode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = runCatching {
                RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                    play()
                }
            }.getOrNull()
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            vibrator?.let {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 900), 0))
                vibrating = true
            }
        }
    }

    fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        if (vibrating) {
            vibrator?.cancel()
            vibrating = false
        }
    }

    /** Call audio on: focus, communication mode, and the best route to start with. */
    fun start(video: Boolean): AudioRoute {
        stopRinging()
        val audio = audio ?: return AudioRoute.Speaker
        if (!active) {
            active = true
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            audio.requestAudioFocus(request)
            focus = request
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        val available = routes()
        val route = when {
            AudioRoute.Bluetooth in available -> AudioRoute.Bluetooth
            AudioRoute.Headset in available -> AudioRoute.Headset
            video -> AudioRoute.Speaker
            else -> AudioRoute.Earpiece
        }
        setRoute(route)
        return route
    }

    /** The outputs a call can use right now. */
    fun routes(): List<AudioRoute> {
        val audio = audio ?: return listOf(AudioRoute.Speaker)
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices.map { it.type }
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }.toSet()
        return buildList {
            if (types.any { it in headsets }) add(AudioRoute.Headset) else add(AudioRoute.Earpiece)
            add(AudioRoute.Speaker)
            if (types.any { it in bluetooth }) add(AudioRoute.Bluetooth)
        }
    }

    fun setRoute(route: AudioRoute) {
        val audio = audio ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wanted = when (route) {
                    AudioRoute.Speaker -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    AudioRoute.Earpiece -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                    AudioRoute.Bluetooth -> bluetooth
                    AudioRoute.Headset -> headsets
                }
                val device = audio.availableCommunicationDevices.firstOrNull { it.type in wanted }
                if (device != null) audio.setCommunicationDevice(device) else audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                when (route) {
                    AudioRoute.Bluetooth -> {
                        audio.isSpeakerphoneOn = false
                        audio.startBluetoothSco()
                        audio.isBluetoothScoOn = true
                    }
                    else -> {
                        if (audio.isBluetoothScoOn) {
                            audio.isBluetoothScoOn = false
                            audio.stopBluetoothSco()
                        }
                        audio.isSpeakerphoneOn = route == AudioRoute.Speaker
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MaMi.Call", "could not switch audio to $route", e)
        }
    }

    /** Screen off when the phone is at the ear (voice calls on the earpiece). */
    @SuppressLint("WakelockTimeout") // released when the call ends
    fun proximity(on: Boolean) {
        if (on) {
            if (proximity?.isHeld == true) return
            val power = power ?: return
            if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "MaMi:call").also { it.acquire() }
        } else {
            proximity?.let { if (it.isHeld) it.release() }
            proximity = null
        }
    }

    /** Everything back to normal. */
    fun stop() {
        stopRinging()
        proximity(false)
        if (!active) return
        active = false
        val audio = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                if (audio.isBluetoothScoOn) {
                    audio.isBluetoothScoOn = false
                    audio.stopBluetoothSco()
                }
                audio.isSpeakerphoneOn = false
            }
        }
        audio.mode = AudioManager.MODE_NORMAL
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        val bluetooth = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        val headsets = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
