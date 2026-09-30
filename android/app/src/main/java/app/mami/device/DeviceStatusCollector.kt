package app.mami.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import app.mami.core.DeviceStatus
import app.mami.core.NetworkKind
import app.mami.core.QuickStatus
import app.mami.core.RingerMode
import app.mami.core.ShareKind
import app.mami.data.SavedQuickStatus
import java.time.ZoneId
import java.util.TimeZone

/** Everything we can read about this phone, before the sharing choices are applied. */
data class RawStatus(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val network: NetworkKind?,
    val signalLevel: Int?,
    val ringer: RingerMode?,
    val doNotDisturb: Boolean?,
    val timezone: String,
    val utcOffsetMinutes: Int,
)

/** Reads battery, connection, signal and ringer state. Needs no dangerous permissions. */
class DeviceStatusCollector(private val context: Context) {

    fun read(): RawStatus {
        val now = System.currentTimeMillis()
        val (battery, charging) = battery()
        val (network, signal) = network()
        return RawStatus(
            batteryPercent = battery,
            charging = charging,
            network = network,
            signalLevel = signal,
            ringer = ringer(),
            doNotDisturb = doNotDisturb(),
            timezone = ZoneId.systemDefault().id,
            utcOffsetMinutes = TimeZone.getDefault().getOffset(now) / 60_000,
        )
    }

    /** The status to send, containing only what the person chose to share. */
    fun toShared(raw: RawStatus, shares: Set<ShareKind>, quick: SavedQuickStatus?): DeviceStatus {
        val battery = ShareKind.BATTERY in shares
        val network = ShareKind.NETWORK in shares
        val ringer = ShareKind.RINGER in shares
        val time = ShareKind.LOCAL_TIME in shares
        return DeviceStatus(
            capturedAtMs = System.currentTimeMillis(),
            batteryPercent = raw.batteryPercent.takeIf { battery },
            charging = raw.charging.takeIf { battery },
            network = raw.network.takeIf { network },
            signalLevel = raw.signalLevel.takeIf { network },
            ringer = raw.ringer.takeIf { ringer },
            doNotDisturb = raw.doNotDisturb.takeIf { ringer },
            timezone = raw.timezone.takeIf { time },
            utcOffsetMinutes = raw.utcOffsetMinutes.takeIf { time },
            quickStatus = quick?.let { QuickStatus(it.emoji, it.label, it.untilMs) },
            shares = shares.filter { it != ShareKind.UNKNOWN },
            platform = "android",
        )
    }

    private fun battery(): Pair<Int?, Boolean?> {
        // A null receiver just reads the last (sticky) battery broadcast.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: intent?.let {
                val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (raw >= 0 && scale > 0) raw * 100 / scale else null
            }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> manager?.isCharging
        }
        return level to charging
    }

    private fun network(): Pair<NetworkKind?, Int?> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null to null
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return NetworkKind.OFFLINE to null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI to wifiLevel(capabilities)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR to cellularLevel()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET to null
            else -> NetworkKind.OTHER to null
        }
    }

    private fun wifiLevel(capabilities: NetworkCapabilities): Int? {
        val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi
        } ?: return null
        return when {
            rssi >= -55 -> 4
            rssi >= -66 -> 3
            rssi >= -77 -> 2
            rssi >= -88 -> 1
            else -> 0
        }
    }

    private fun cellularLevel(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            context.getSystemService(TelephonyManager::class.java)?.signalStrength?.level
        }.getOrNull()
    }

    private fun ringer(): RingerMode? = when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        else -> null
    }

    private fun doNotDisturb(): Boolean? {
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: return null
        return when (filter) {
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> null
            NotificationManager.INTERRUPTION_FILTER_ALL -> false
            else -> true
        }
    }
}
