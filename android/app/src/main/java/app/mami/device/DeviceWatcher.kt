package app.mami.device

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat

/**
 * While the app process is alive, notices changes worth telling the partner
 * about right away: plugging in, battery level, silent mode, Do Not Disturb,
 * and the network coming and going.
 */
class DeviceWatcher(
    private val context: Context,
    private val onDeviceChanged: () -> Unit,
    private val onNetworkAvailable: () -> Unit,
    private val onScreenOff: () -> Unit = {},
    private val onUnlocked: () -> Unit = {},
) {
    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onDeviceChanged()
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Screen off and unlock, to notice waking up in the morning.
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> onScreenOff()
                        Intent.ACTION_USER_PRESENT -> onUnlocked()
                    }
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onNetworkAvailable()
                    onDeviceChanged()
                }

                override fun onLost(network: Network) = onDeviceChanged()
            },
        )
    }
}
